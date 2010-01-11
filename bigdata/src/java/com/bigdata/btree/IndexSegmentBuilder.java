/**

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Dec 5, 2006
 */

package com.bigdata.btree;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.NumberFormat;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.apache.log4j.Logger;

import com.bigdata.LRUNexus;
import com.bigdata.btree.data.IAbstractNodeData;
import com.bigdata.btree.data.ILeafData;
import com.bigdata.btree.data.INodeData;
import com.bigdata.btree.raba.IRaba;
import com.bigdata.btree.raba.MutableKeyBuffer;
import com.bigdata.btree.raba.MutableValueBuffer;
import com.bigdata.btree.view.FusedView;
import com.bigdata.cache.IGlobalLRU.ILRUCache;
import com.bigdata.io.AbstractFixedByteArrayBuffer;
import com.bigdata.io.DirectBufferPool;
import com.bigdata.io.SerializerUtil;
import com.bigdata.journal.TemporaryRawStore;
import com.bigdata.mdi.IResourceMetadata;
import com.bigdata.mdi.LocalPartitionMetadata;
import com.bigdata.mdi.SegmentMetadata;
import com.bigdata.rawstore.Bytes;
import com.bigdata.rawstore.IAddressManager;
import com.bigdata.rawstore.IRawStore;
import com.bigdata.rawstore.WormAddressManager;

/**
 * Builds an {@link IndexSegment} given a source btree and a target branching
 * factor. There are two main use cases:
 * <ol>
 * 
 * <li>Evicting a key range of an index into an optimized on-disk index. In this
 * case, the input is a {@link BTree} that is ideally backed by a fully buffered
 * {@link IRawStore} so that no random reads are required.</li>
 * 
 * <li>Merging index segments. In this case, the input is typically records
 * emerging from a merge-sort. There are two distinct cases here. In one, we
 * simply have raw records that are being merged into an index. This might occur
 * when merging two key ranges or when external data are being loaded. In the
 * other case we are processing two time-stamped versions of an overlapping key
 * range. In this case, the more recent version may have "delete" markers
 * indicating that a key present in an older version has been deleted in the
 * newer version. Also, key-value entries in the newer version replaced (rather
 * than are merged with) key-value entries in the older version. If an entry
 * history policy is defined, then it must be applied here to cause key-value
 * whose retention is no longer required by that policy to be dropped.</li>
 * 
 * </ol>
 * 
 * Note: In order for the nodes to be written in a contiguous block we either
 * have to buffer them in memory or have to write them onto a temporary file and
 * then copy them into place after the last leaf has been processed. The code
 * abstracts this decision using a {@link TemporaryRawStore} for this purpose.
 * This space demand was not present in West's algorithm because it did not
 * attempt to place the leaves contiguously onto the store.
 * 
 * <p>
 * 
 * Note: The use of up to three {@link TemporaryRawStore}s per index segment
 * build can raise the demand on the {@link DirectBufferPool}, for example if a
 * number of concurrent index builds are occurring on a data service with a
 * large #of index partitions. One choice is to limit the parallelism of the
 * asynchronous overflow operation.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id: IndexSegmentBuilder.java 2265 2009-10-26 12:51:06Z thompsonbry
 *          $
 * 
 * @see "Post-order B-Tree Construction" by Lawerence West, ACM 1992. Note that
 *      West's algorithm is for a b-tree (values are stored on internal stack as
 *      well as leaves), not a b+-tree (values are stored only on the leaves).
 *      Our implementation is therefore an adaptation.
 * 
 * @see "Batch-Construction of B+-Trees" by Kim and Won, ACM 2001. The approach
 *      outlined by Kim and Won is designed for B+-Trees, but it appears to be
 *      less efficient on first glance.
 * 
 * @see IndexSegment
 * @see IndexSegmentFile
 * @see IndexSegmentCheckpoint
 * @see IndexSegmentMerger
 * 
 * @todo There are at least three design alternatives for handling the fast
 *       range count during builds: (A) do an exact range count instead and
 *       generate a perfect plan; (B) fully buffer the source iterator into
 *       byte[][] keys, byte[][] vals, boolean[] deleteMarkers, and long[]
 *       versionTimestamps and generate an exact plan, consuming the buffered
 *       byte[]s directly from RAM; and (C) use the fast range count to generate
 *       a plan based on an overestimate of the tuple count and then apply a
 *       variety of hacks when the source iterator is exhausted to make the
 *       output B+Tree usable, but not well formed.
 *       <p>
 *       The disadvantage of (C) is that the hacks break encapsulation and can
 *       leak into the API where operations such as the sibling of a node could
 *       return an empty leaf. It seems like it would be difficult to have
 *       confidence that the B+Tree API was fully insulated against the effects
 *       of ill-formed {@link IndexSegment}s. In fact, I do not like these side
 *       effects enough that it could be worthwhile to back the changes out of
 *       the Node and Leaf classes.
 *       <p>
 *       The disadvantage of (A) is that it requires two passes over the source
 *       view. Those passes could cause churn in the global LRU and could defeat
 *       caching for a view approaching the nominal size for a split.
 *       <p>
 *       The disadvantage of (B) is that it requires more memory. If the JVM is
 *       tight on memory, then we could single thread builds, merges, and splits
 *       or fall back to (A). If the source view is much larger than the nominal
 *       size (or the fast range count is much larger than we would expect) then
 *       we could fall back to (A). The memory burden could be eased if
 *       copyTuple() was overridden to move the byte[] key and byte[] val
 *       references, clearing them from the source byte[][] keys and byte[][]
 *       vals as they were consumed.
 *       <p>
 *       Another optimization for (B) is to write the leaves directly on the
 *       backing file using double-buffering or directly updating the prior/next
 *       addrs and to buffer the nodes in memory, writing them once the leaves
 *       are finished.
 *       <p>
 *       It occurs to me that if we have the data in a bunch of byte[][]s we
 *       could conceivable get this stuff organized to the point where each
 *       tuple and child in the output B+Tree could be assigned to a thread and
 *       each leaf and node to a core on a GPU. At that point, we run the
 *       "build" in parallel, including the coding of the leaves and the emit
 *       them onto the output file in parallel once we know their final size on
 *       the disk and have their prior/next addr field values.
 *       <p>
 *       The B+Tree iterator on the fused view of the journal and index segments
 *       is effectively an incremental merge of the source iterators. However,
 *       the iterator has a [capacity] parameter which is a hint for
 *       materialization. We could do this as a parallel merge sort of the
 *       leaves spanned of the key range with a restriction for the capacity.
 *       That could also be done on a GPU. The same iterator is used as the
 *       source for a compacting merge. If we tunnel the representation to
 *       something like the byte[][] keys, byte[][] vals, boolean[]
 *       deleteMarkers, long[] revisionTimestamps, then we could do a merge sort
 *       of the leaves from the (ordered) view and then do a parallel build
 *       segment from the backing representation. We could do that build step in
 *       java or on a GPU.
 */
public class IndexSegmentBuilder implements Callable<IndexSegmentCheckpoint> {
    
    /**
     * Logger.
     */
    protected static final Logger log = Logger
            .getLogger(IndexSegmentBuilder.class);

    /**
     * Error message when the #of tuples in the {@link IndexSegment} would
     * exceed {@link Integer#MAX_VALUE}.
     * <p>
     * Note: This is not an inherent limit in the {@link IndexSegment} but
     * rather a limit in the {@link IndexSegmentPlan} (and perhaps the
     * {@link IndexSegmentBuilder}) which presumes that the entry count is an
     * <code>int</code> rather than a <code>long</code>.
     */
    protected static final String ERR_TOO_MANY_TUPLES = "Too many tuples";
    
    /**
     * Warning message when the index segment will be empty.
     */
    protected static final String ERR_NO_TUPLES = "No tuples";
    
    /**
     * The file mode used to open the file on which the {@link IndexSegment} is
     * written.
     */
    final String mode = "rw"; // also rws or rwd
    
    /**
     * The file specified by the caller on which the {@link IndexSegment} is
     * written.
     */
    public final File outFile;
    
    /**
     * The value specified to the ctor.
     */
    final public int entryCount;
    
    /**
     * The iterator specified to the ctor. This is the source for the keys and
     * values that will be written onto the generated {@link IndexSegment}.
     */
    final private ITupleIterator<?> entryIterator;
    
    /**
     * The commit time associated with the view from which the
     * {@link IndexSegment} is being generated (from the ctor). This value is
     * written into {@link IndexSegmentCheckpoint#commitTime}.
     */
    final public long commitTime;

    /**
     * <code>true</code> iff the generated {@link IndexSegment} will
     * incorporate all state for the source index (partition) as of the
     * specified <i>commitTime</i>.
     * <p>
     * Note: This flag is written into the {@link IndexSegmentCheckpoint} but it
     * has no other effect on the build process.
     */
    final public boolean compactingMerge;
    
    /**
     * A copy of the metadata object provided to the ctor. This object is
     * further modified before being written on the
     * {@link IndexSegmentStore}.
     */
    final public IndexMetadata metadata;
    
    /**
     * <code>true</code> iff the source index is isolatable (supports both
     * deletion markers and version timestamps).
     */
    final boolean isolatable;
    
    /**
     * <code>true</code> iff the source index has delete markers enabled.
     * <p>
     * Note: delete markers are ONLY copied for an incremental build (when
     * {@link #compactingMerge} is <code>false</code>).
     */
    final boolean deleteMarkers;
    
    /**
     * <code>true</code> iff the source index has tuple revision timestamps
     * enabled.
     */
    final boolean versionTimestamps;
    
    /**
     * The unique identifier for the generated {@link IndexSegment} resource.
     */
    final public UUID segmentUUID;

    /**
     * The cache for the generated {@link IndexSegmentStore}. When non-
     * <code>null</code> the generated {@link INodeData} objects will be placed
     * into the cache, which is backed by a shared LRU. This helps to reduce
     * latency when an index partition built or merge operation finishes and the
     * index partition view is updated since the data will already be present in
     * the cache. Generating the index segment will drive evictions from the
     * shared LRU, but those will be the least recently used records and the new
     * {@link IndexSegmentStore} is often hot as soon as it is generated.
     * <p>
     * Note: If the build fails, then the cache will be cleared.
     * 
     * @todo The {@link IndexMetadata} and the {@link BloomFilter} should be in
     * the {@link #storeCache} as well. Make sure that we do this for read and
     * write for both the {@link BTree} and the {@link IndexSegment}.
     */
    final private ILRUCache<Long, Object> storeCache;

    /**
     * Used to serialize the nodes and leaves of the output tree.
     */
    final private NodeSerializer nodeSer;

    /**
     * Note: The offset bits on the {@link IndexSegmentFileStore} does NOT
     * have to agree with the offset bits on the source store. However, it
     * must be large enough to handle the large branching factors typically
     * associated with an {@link IndexSegment} vs a {@link BTree}. Further,
     * if blobs are to be copied into the index segment then it generally
     * must be large enough for those blobs (up to 64M per record).
     * <p>
     * Note: The same #of offset bits MUST be used by the temporary stores
     * that we use to buffer nodes, leaves, and blobs as are used by the
     * generated index segment!
     */
    final int offsetBits = WormAddressManager.SCALE_OUT_OFFSET_BITS;
    
    /**
     * The {@link IAddressManager} used to form addresses for the generated
     * file. Addresses are formed from a byteCount and an <em>encoded</em>
     * offset comprised of a relative offset into a known region and the region
     * identifier.
     * 
     * @see IndexSegmentRegion
     * @see IndexSegmentAddressManager
     */
    final private WormAddressManager addressManager;

    /**
     * The bloom filter iff we build one (errorRate != 0.0).
     */
    final IBloomFilter bloomFilter;
    
    /**
     * The file on which the {@link IndexSegment} is written. The file is closed
     * regardless of the outcome of the operation.
     */
    protected RandomAccessFile out = null;
    
    /**
     * The {@link IndexSegmentCheckpoint} record written on the
     * {@link IndexSegmentStore}.
     */
    private IndexSegmentCheckpoint checkpoint;
    
    /**
     * The {@link IndexSegmentCheckpoint} record written on the
     * {@link IndexSegmentStore}.
     */
    public IndexSegmentCheckpoint getCheckpoint() {
        
        return checkpoint;
        
    }
    
    /**
     * The buffer used to hold leaves so that they can be evicted en mass onto a
     * region of the {@link #outFile}.
     */
    protected TemporaryRawStore leafBuffer;
    
    /**
     * The buffer used to hold nodes so that they can be evicted en mass onto
     * a region of the {@link #outFile}.
     */
    protected TemporaryRawStore nodeBuffer;
    
    /**
     * The optional buffer used to hold records referenced by index entries. In
     * order to use this buffer the {@link IndexMetadata} MUST specify an
     * {@link IOverflowHandler}.
     */
    protected TemporaryRawStore blobBuffer;
    
    private final IOverflowHandler overflowHandler;
    
    /**
     * The encoded address of the first leaf written on the
     * {@link IndexSegmentStore} (there is always at least one, even if it is
     * the root leaf).
     * <p>
     * Note: A copy of this value is preserved by
     * {@link IndexSegmentCheckpoint#addrFirstLeaf}.
     */
    private long addrFirstLeaf = 0L;

    /**
     * The encoded address of the last leaf written on the
     * {@link IndexSegmentStore} (there is always at least one, even if it is
     * the root leaf).
     * <p>
     * Note: A copy of this value is preserved by
     * {@link IndexSegmentCheckpoint#addrLastLeaf}.
     */
    private long addrLastLeaf = 0L;

//    /**
//     * The offset in the output file of the last leaf written onto that file.
//     * Together with {@link #lastLeafSize} this is used to compute the
//     * address of the prior leaf.
//     */
//    long lastLeafOffset = -1L;
//    
//    /**
//     * The size in bytes of the last leaf written onto the output file (the size
//     * of the compressed record that is actually written onto the output file
//     * NOT the size of the serialized leaf before it is compressed). Together
//     * with {@link #lastLeafOffset} this is used to compute the address of the
//     * prior leaf.
//     */
//    int lastLeafSize = -1;

    /**
     * Tracks the maximum length of any serialized node or leaf.  This is used
     * to fill in one of the {@link IndexSegmentCheckpoint} fields.
     */
    int maxNodeOrLeafLength = 0;

    /**
     * The #of tuples written for the output tree.
     */
    int ntuplesWritten;
    
    /**
     * The #of nodes written for the output tree. This will be zero if all
     * entries fit into a root leaf.
     */
    int nnodesWritten = 0;
    
    /**
     * The #of leaves written for the output tree.
     */
    int nleavesWritten = 0;
    
    /**
     * The #of nodes or leaves that have been written out in each level of the
     * tree.
     * 
     * @see IndexSegmentPlan#numInLevel
     */
    final int writtenInLevel[];

    /**
     * The stack of nodes that are currently being populated. The first N-1
     * elements in this array are always nodes while the last element is always
     * a leaf ({@link #leaf} is the same reference as the last element in this
     * array). The nodes and the leaf in this array are reused rather than being
     * reallocated.
     */
    final AbstractSimpleNodeData[] stack;
    
    /**
     * The current leaf that is being populated from the source btree. This leaf
     * is reused for each output leaf rather than being reallocated. In the
     * degenerate case when the output btree is a single root leaf then this
     * will be that leaf. This reference is always the same as the last
     * reference in {@link #stack}.
     */
    final SimpleLeafData leaf;

    /**
     * The plan for building the B+-Tree.
     */
    final public IndexSegmentPlan plan;
    
    /**
     * The timestamp in milliseconds when {@link #call()} was invoked. 
     */
    private long begin_build;
    
    /**
     * The timestamp in milliseconds when {@link #call()} was invoked -or-
     * ZERO (0L) if {@link #call()} has not been invoked. 
     */
    public long getStartTime() {
        
        return begin_build;
        
    }
    
    /**
     * The time to setup the index build, including the generation of the index
     * plan and the initialization of some helper objects.
     */
    public final long elapsed_setup;
    
    /**
     * The time to write the nodes and leaves into their respective buffers, not
     * including the time to transfer those buffered onto the output file.
     */
    public long elapsed_build;
    
    /**
     * The time to write the nodes and leaves from their respective buffers
     * onto the output file and synch and close that output file.
     */
    public long elapsed_write;

    /**
     * The process runtime in milliseconds.
     */
    public long elapsed;

    /**
     * The data throughput rate in megabytes per second.
     */
    public float mbPerSec;

    /**
     * Builder factory will build an {@link IndexSegment} from an index
     * (partition). Delete markers are propagated to the {@link IndexSegment}
     * unless <i>compactingMerge</i> is <code>true</code>.
     * 
     * @param name
     *            The name of the index (for non-scale-out indices) or the name
     *            of the index partition (for scale-out indices). DO NOT specify
     *            the name of the scale-out index!
     * @param src
     *            A view of the index partition as of the <i>createTime</i>.
     *            When <i>compactingMerge</i> is <code>false</code> then this
     *            MUST be a single {@link BTree} since incremental builds are
     *            only support for a {@link BTree} source while compacting
     *            merges are defined for any {@link IIndex}.
     * @param outFile
     *            The file on which the {@link IndexSegment} will be written.
     *            The file MAY exist, but if it exists then it MUST be empty.
     * @param compactingMerge
     *            When <code>true</code> the caller asserts that <i>src</i>
     *            is a {@link FusedView} and deleted index entries WILL NOT be
     *            included in the generated {@link IndexSegment}. Otherwise, it
     *            is assumed that the only select component(s) of the index
     *            partition view are being exported onto an {@link IndexSegment}
     *            and deleted index entries will therefore be propagated to the
     *            new {@link IndexSegment} (aka an incremental build).
     * @param createTime
     *            The commit time associated with the view from which the
     *            {@link IndexSegment} is being generated. This value is written
     *            into {@link IndexSegmentCheckpoint#commitTime}.
     * @param fromKey
     *            The lowest key that will be included (inclusive). When
     *            <code>null</code> there is no lower bound.
     * @param toKey
     *            The first key that will be included (exclusive). When
     *            <code>null</code> there is no upper bound.
     * 
     * @return An object which can be used to construct the {@link IndexSegment}.
     * 
     * @throws IOException
     */
    public static IndexSegmentBuilder newInstance(final String name,
            final ILocalBTreeView src, final File outFile, final File tmpDir,
            final boolean compactingMerge, final long createTime,
            final byte[] fromKey, final byte[] toKey) throws IOException {

        if (name == null)
            throw new IllegalArgumentException();

        if (src == null)
            throw new IllegalArgumentException();

        if (outFile == null)
            throw new IllegalArgumentException();

        if (tmpDir == null)
            throw new IllegalArgumentException();

        if (createTime <= 0L)
            throw new IllegalArgumentException();

        // metadata for that index / index partition.
        final IndexMetadata indexMetadata = src.getIndexMetadata();

        /*
         * Use the range iterator to get an exact entry count for the view.
         */
        final int nentries;
        final int flags;
        if (compactingMerge) {

            /*
             * For a compacting merge the delete markers are ignored so they
             * will NOT be transferred to the new index segment.
             */

            flags = IRangeQuery.DEFAULT;

            // FIXME use the fast range count.
            final long n = src.rangeCountExact(fromKey, toKey);
            
            if (n > Integer.MAX_VALUE) {

                throw new UnsupportedOperationException(ERR_TOO_MANY_TUPLES);

            }

            nentries = (int) n;

            if (log.isInfoEnabled())
                log.info("Compacting merge: name=" + name
                        + ", non-deleted index entries=" + nentries);

        } else {

            /*
             * For an incremental build the deleted tuples are propagated to the
             * new index segment. This is required in order for the fact that
             * those tuples were deleted as of the commitTime to be retained by
             * the generated index segment.
             */

            flags = IRangeQuery.DEFAULT | IRangeQuery.DELETED;

            // FIXME use the fast range count.
            final long n = src.rangeCountExactWithDeleted(fromKey, toKey);

            if (n > Integer.MAX_VALUE) {

                throw new UnsupportedOperationException(ERR_TOO_MANY_TUPLES);

            }

            nentries = (int) n;

            if (log.isInfoEnabled())
                log.info("Incremental build: name=" + name + ", nentries="
                        + nentries + ", rangeCountExactWithDeleted=" + n);

        }

        /*
         * Iterator reading the source tuples to be copied to the index segment.
         * 
         * Note: The DELETED flag was set above unless this is a compacting
         * merge. That is necessary to ensure that deleted tuples are preserved
         * when the index segment does not reflect the total history of a view.
         */
        final ITupleIterator<?> itr = src.rangeIterator(fromKey, toKey,
                0/* capacity */, flags, null/* filter */);

        // Setup the index segment build operation.
        final IndexSegmentBuilder builder = new IndexSegmentBuilder(//
                outFile, //
                tmpDir, //
                nentries, //
                itr,     //
                indexMetadata.getIndexSegmentBranchingFactor(),//
                indexMetadata,//
                createTime,//
                compactingMerge//
        );

        return builder;

    }

    /**
     * <p>
     * Designated constructor sets up a build of an {@link IndexSegment} for
     * some caller defined read-only view.
     * </p>
     * <p>
     * Note: The caller must determine whether or not deleted index entries are
     * present in the view. The <i>entryCount</i> MUST be the exact #of index
     * entries that are visited by the given iterator. In general, this is not
     * difficult. However, if a compacting merge is desired (that is, if you are
     * trying to generate a view containing only the non-deleted entries) then
     * you MUST explicitly count the #of entries that will be visited by the
     * iterator, e.g., it will require two passes over the iterator to setup the
     * index build operation.
     * </p>
     * <p>
     * Note: With a branching factor of 4096 a tree of height 2 (three levels)
     * could address 68,719,476,736 entries - well beyond what we want in a
     * given index segment! Well before that the index segment should be split
     * into multiple files. The split point should be determined by the size of
     * the serialized leaves and nodes, e.g., the amount of data on disk
     * required by the index segment and the amount of memory required to fully
     * buffer the index nodes. While the size of a serialized node can be
     * estimated easily, the size of a serialized leaf depends on the kinds of
     * values stored in that index. The actual sizes are recorded in the
     * {@link IndexSegmentCheckpoint} record in the header of the
     * {@link IndexSegment}.
     * </p>
     * 
     * @param outFile
     *            The file on which the index segment is written. The file MAY
     *            exist but MUST have zero length if it does exist (this permits
     *            you to use the temporary file facility to create the output
     *            file).
     * @param tmpDir
     *            The temporary directory in data are buffered during the build
     *            (optional - the default temporary directory is used if this is
     *            <code>null</code>).
     * @param entryCount
     *            The #of entries that will be visited by the iterator. This MAY
     *            be an overestimate. By allowing an overestimate you can pass
     *            in the fast range count for a view and generate a "good" (but
     *            not perfect) index segment. This allows the caller to avoid
     *            the cost of obtaining the exact range count for the view.
     *            However, if the caller reports a rangeCount which is TOO SMALL
     *            for the actual data then the index segment build will fail
     *            because the {@link IndexSegmentPlan} will not contain enough
     *            nodes/leaves to hold the tuples.
     * @param entryIterator
     *            Visits the index entries in key order that will be written
     *            onto the {@link IndexSegment}.
     * @param m
     *            The branching factor for the generated tree. This can be
     *            chosen with an eye to minimizing the height of the generated
     *            tree. (Small branching factors are permitted for testing, but
     *            generally you want something relatively large.)
     * @param metadata
     *            The metadata record for the source index. A copy will be made
     *            of this object. The branching factor in the generated tree
     *            will be overridden to <i>m</i>.
     * @param commitTime
     *            The commit time associated with the view from which the
     *            {@link IndexSegment} is being generated. This value is written
     *            into {@link IndexSegmentCheckpoint#commitTime}.
     * @param compactingMerge
     *            <code>true</code> iff the generated {@link IndexSegment} will
     *            incorporate all state for the source index (partition) as of
     *            the specified <i>commitTime</i>. This flag is written into the
     *            {@link IndexSegmentCheckpoint} but does not otherwise effect
     *            the build process.
     * 
     * @throws IOException
     */
    public IndexSegmentBuilder(//
            final File outFile,//
            final File tmpDir,//
            final int entryCount,//
            final ITupleIterator<?> entryIterator, //
            final int m,//
            IndexMetadata metadata,//
            final long commitTime,//
            final boolean compactingMerge// 
            )
            throws IOException {

        if (outFile == null)
            throw new IllegalArgumentException();

        if (tmpDir == null)
            throw new IllegalArgumentException();
        
        if (entryCount < 0)
            throw new IllegalArgumentException();

//        if (entryCount == 0 && !compactingMerge) {
//         
//            /*
//             * Note: A zero entry count is allowed for a compacting merge. This
//             * can arise when all tuples in an index (partition) have been
//             * deleted. It is impossible to detect this condition before we
//             * explicitly range count the tuples (including any delete markers).
//             * Rather than forcing the caller to handle this via a thrown
//             * exception it is significantly easier to generate an empty
//             * IndexSegment.
//             */
//            
//            throw new IllegalArgumentException();
//            
//        }

        if (entryCount == 0)
            log.warn(ERR_NO_TUPLES);
        
        if (entryIterator == null)
            throw new IllegalArgumentException();
        
        if (commitTime <= 0L)
            throw new IllegalArgumentException();

        final long begin_setup = System.currentTimeMillis();

        // the UUID assigned to this index segment file.
        this.segmentUUID = UUID.randomUUID();

        this.entryCount = entryCount;
        
        this.entryIterator = entryIterator;
        
        /*
         * Make a copy of the caller's metadata.
         * 
         * Note: The callers's reference is replaced by a reference to the clone
         * in order to avoid accidental modifications to the caller's metadata
         * object.
         */
        this.metadata = metadata = metadata.clone();
        {
            
            final LocalPartitionMetadata pmd = this.metadata.getPartitionMetadata();
            
            if (pmd != null) {
        
                /*
                 * Copy the local partition metadata, but do not include the
                 * resource metadata identifying the resources that comprise the
                 * index partition view. that information is only stored on the
                 * BTree, not on the IndexSegment.
                 */

                this.metadata.setPartitionMetadata(
                        new LocalPartitionMetadata(//
                                pmd.getPartitionId(),//
                                pmd.getSourcePartitionId(),//
                                pmd.getLeftSeparatorKey(),//
                                pmd.getRightSeparatorKey(),//
                                null, // No resource metadata for indexSegment.
                                pmd.getIndexPartitionCause(),
                                pmd.getHistory()+
                                "build("+pmd.getPartitionId()+",compactingMerge="+compactingMerge+") "
                        )
                        );
                
            }
            
        }
        
        // true iff the source index is isolatable.
        this.isolatable = metadata.isIsolatable();
        
        /*
         * true iff the source index maintains tuple revision timestamps.
         */
        this.versionTimestamps = metadata.getVersionTimestamps();

        /*
         * true iff the source index supports delete markers (but they will be
         * copied IFF this is an incremental build).
         */ 
        this.deleteMarkers = metadata.getDeleteMarkers(); 

        //
        this.commitTime = commitTime;

        this.compactingMerge = compactingMerge;
        
        /*
         * Override the branching factor on the index segment.
         * 
         * Note: this override is a bit dangerous since it might propagate back
         * to the mutable btree, which could hurt performance through the use of
         * a too large branching factor on the journal. However, the metadata
         * index stores the template metadata for the scale-out index and if you
         * use either that or the metadata record from an existing BTree then
         * this should never be a problem.
         */
        this.metadata.setBranchingFactor(m);
        
        /*
         * @todo The override of the BTree class name does not make much sense
         * here. Either we should strongly discourage further subclassing of
         * BTree and IndexSegment or we should allow the subclass to be named
         * for both the mutable btree and the read-only index segment.
         */
        this.metadata.setBTreeClassName(IndexSegment.class.getName());

        this.addressManager = new WormAddressManager(offsetBits);

        /*
         * The INodeData cache for the generated index segment store.
         * 
         * @todo LIRS: The index segment builder should perhaps only drive into
         * the shared LRU those records which were already hot. Figuring this
         * out will break encapsulation. Since the branching factor is not the
         * same, and since the source is a view, "hot" has to be interpreted in
         * terms of key ranges which are hot. As a workaround in a memory
         * limited system you can configure the LRUNexus so that the build will
         * not drive the records into the cache. [LIRS would partly address this
         * by not evicting records from the cache which are hot.]
         */
        storeCache = (LRUNexus.INSTANCE != null && LRUNexus
                .getIndexSegmentBuildPopulatesCache()) //
                ? LRUNexus.INSTANCE.getCache(segmentUUID, addressManager)//
                : null//
                ;

        /*
         * Create the index plan and do misc setup.
         */
        {

            // Create a plan for generating the output tree.
            plan = new IndexSegmentPlan(m, entryCount);

            /*
             * Setup a stack of nodes (one per non-leaf level) and one leaf.
             * These are filled in based on the plan and the entries visited in
             * the source btree. Nodes and leaves are written out to their
             * respective channel each time they are complete as defined by the
             * plan given the #of children assigned to a node or the #of keys
             * assigned to a leaf.
             */

            stack = new AbstractSimpleNodeData[plan.height + 1];

            // Note: assumes defaults to all zeros.
            writtenInLevel = new int[plan.height + 1];

            for (int h = 0; h < plan.height; h++) {

                final SimpleNodeData node = new SimpleNodeData(h, plan.m,
                        versionTimestamps);

                node.max = plan.numInNode[h][0];

                stack[h] = node;

            }

            // the output leaf (reused for each leaf we populate).

            leaf = new SimpleLeafData(plan.height, plan.m, metadata);

            leaf.max = entryCount == 0 ? 0 : plan.numInNode[plan.height][0];

            stack[plan.height] = leaf;

            /*
             * Setup optional bloom filter.
             * 
             * Note: For read-only {@link IndexSegment} we always know the #of
             * keys exactly at the time that we provision the bloom filter. This
             * makes it easy for us to tune the filter for a desired false
             * positive rate.
             */
            if (metadata.getBloomFilterFactory() != null && plan.nentries > 0) {

                // the desired error rate for the bloom filter.
                final double p = metadata.getBloomFilterFactory().p;

                // create the bloom filter.
                bloomFilter = new BloomFilter(plan.nentries, p);

            } else {
                
                bloomFilter = null;
                
            }

            /*
             * Used to serialize the nodes and leaves for the output tree.
             */
            nodeSer = new NodeSerializer(//
                    /*
                     * Note: it does not seem like there should be any
                     * interaction between various IAddressSerializer strategies
                     * and the manner in which we encode the region (BASE, NODE,
                     * or BLOB) into the offset of addresses for the index
                     * segment store. The offset is effectively left-shifted by
                     * two bits to encode the region, there by reducing the
                     * maximum possible byte offset within any region (including
                     * BASE). However, that should not pose problems for any
                     * IAddressSerializer strategy as long as it accepts any
                     * legal [byteCount] and [offset] - it is just that our
                     * offsets are essentially 4x larger than they would be
                     * otherwise.
                     */
                    addressManager,//
                    NOPNodeFactory.INSTANCE,//
                    plan.m,// the output branching factor.
                    0, // initialBufferCapacity - will be estimated.
                    metadata, //
                    false, // NOT read-only (we are using it for writing).
                    metadata.getIndexSegmentRecordCompressorFactory()
                    );

        }
    
        this.overflowHandler = metadata.getOverflowHandler();
        
        this.outFile = outFile;
        
        elapsed_setup = System.currentTimeMillis() - begin_setup;
        
    }
    
    /**
     * Build the {@link IndexSegment} given the parameters specified to the
     * constructor.
     */
    public IndexSegmentCheckpoint call() throws Exception {

        /*
         * Setup for IO.
         */

        begin_build = System.currentTimeMillis();
        
        if (outFile.exists() && outFile.length() != 0L) {
            
            throw new IllegalArgumentException("File exists and is not empty: "
                    + outFile.getAbsoluteFile());
            
        }

        final FileChannel outChannel;

        try {

            /*
             * Open the output channel
             * 
             * @todo get an exclusive lock (FileLock).
             */
            
//            out = FileLockUtility.openFile(outFile, mode, true/*useFileLock*/);
            out = new RandomAccessFile(outFile, mode);
//            
            outChannel = out.getChannel();
//            
//            if (outChannel.tryLock() == null) {
//                
//                throw new IOException("Could not lock file: "
//                        + outFile.getAbsoluteFile());
//                
//            }

            /*
             * Open the leaf buffer. We only do this if there is at least a
             * single root leaf, i.e., if the output tree is not empty.
             */
            leafBuffer = plan.nleaves > 0 ? new TemporaryRawStore(offsetBits)
                    : null;
            
            /*
             * Open the node buffer. We only do this if there will be at least
             * one node written, i.e., the output tree will consist of more than
             * just a root leaf.
             */
            nodeBuffer = plan.nnodes > 0 ? new TemporaryRawStore(offsetBits) : null;

            /*
             * Open buffer for blobs iff an overflow handler was specified.
             */
            blobBuffer = overflowHandler == null ? null
                    : new TemporaryRawStore(offsetBits);

            /*
             * Generate the output B+Tree.
             */
            buildBTree();
            
// Note: These asserts can be violated if the rangeCount overestimates.
//            // Verify that all leaves were written out.
//            assert plan.nleaves == nleavesWritten;
//            
//            // Verify that all nodes were written out.
//            assert plan.nnodes == nnodesWritten;

            elapsed_build = System.currentTimeMillis() - begin_build;
            
            final long begin_write = System.currentTimeMillis();
            
            // write everything out on the outFile.
            checkpoint = writeIndexSegment(outChannel, commitTime);
            
            /*
             * Flush this channel to disk and close the channel. This also
             * releases our lock. We are done and the index segment is ready for
             * use.
             */
            outChannel.force(true);
//            FileLockUtility.closeFile(outFile, out);
            out.close(); // also releases the lock.
////            out = null;

            elapsed_write = System.currentTimeMillis() - begin_write;
            
            /*
             * log run time.
             */
            
            elapsed = (System.currentTimeMillis() - begin_build) + elapsed_setup;

            // data rate in MB/sec.
            mbPerSec = (elapsed == 0 ? 0 : checkpoint.length / Bytes.megabyte32
                    / (elapsed / 1000f));
            
            if(log.isInfoEnabled()) {
            
                final NumberFormat cf = NumberFormat.getNumberInstance();
                
                cf.setGroupingUsed(true);
                
                final NumberFormat fpf = NumberFormat.getNumberInstance();
                
                fpf.setGroupingUsed(false);
                
                fpf.setMaximumFractionDigits(2);

                log.info("finished"
                    + ": total(ms)="+ elapsed//
                    + "= setup("+ elapsed_setup +")"//
                    + "+ build("+ elapsed_build + ")"//
                    + "+ write("+ elapsed_write +")"//
                    + "; branchingFactor=" + plan.m//
                    + ", nentries=("+ ntuplesWritten+ " actual, "+plan.nentries+ " plan)"//
                    + ", nnodes=("+ nnodesWritten+" actual, "+plan.nnodes+" plan)"//
                    + ", nleaves=("+ nleavesWritten+" actual, "+plan.nleaves+" plan)"//
                    + ", length="+ fpf.format(((double) checkpoint.length / Bytes.megabyte32))+ "MB" //
                    + ", rate=" + fpf.format(mbPerSec) + "MB/sec"//
                    );

            }
            
            return checkpoint;
            
        } catch (Exception ex) {
            
            /*
             * Note: The output file is deleted if the build fails.
             */
            deleteOutputFile();
            
            // Re-throw exception
            throw ex;
            
        } catch (Throwable ex) {

            /*
             * Note: The output file is deleted if the build fails.
             */
            deleteOutputFile();

            // Masquerade exception.
            throw new RuntimeException(ex);

        } finally {

            /*
             * make sure that the temporary file gets deleted regardless.
             */
            if (leafBuffer != null && leafBuffer.isOpen()) {
                try {
                    leafBuffer.close(); // also deletes the file if any.
                } catch (Throwable t) {
                    log.warn(t,t);
                }
            }
            
            /*
             * make sure that the temporary file gets deleted regardless.
             */
            if (nodeBuffer != null && nodeBuffer.isOpen()) {
                try {
                    nodeBuffer.close(); // also deletes the file if any.
                } catch (Throwable t) {
                    log.warn(t,t);
                }
            }
            
        }
        
    }

    /**
     * Scan the source tuple iterator in key order writing output leaves onto
     * the index segment file with the new branching factor. We also track a
     * stack of nodes that are being written out concurrently on a temporary
     * channel.
     * <p>
     * The plan tells us the #of values to insert into each leaf and the #of
     * children to insert into each node. Each time a leaf becomes full
     * (according to the plan), we "close" the leaf, writing it out onto the
     * store and obtaining its "address". The "close" logic also takes care of
     * setting the address on the leaf's parent node (if any). If the parent
     * node becomes filled (according to the plan) then it is also "closed".
     * <p>
     * Each time (except the first) that we start a new leaf we record its first
     * key as a separatorKey in the appropriate parent node.
     * <p>
     * Note: The root may be a leaf as a degenerate case.
     * <p>
     * Note: The right most leaf(s) and node(s) MAY underflow and/or MAY never
     * be generated if the expected range count overestimates the actual number
     * of tuples. This is a feature. It makes it possible to do a compacting
     * merge without requiring a tuple scan first to obtain an exact range
     * count. The resulting index segment is still very good, even if it is not
     * perfect (some nodes and leaves may underflow), and it is much faster to
     * produce since we only scan the source data once.
     * <p>
     * There are several consequences of this feature: (A) If the last leaf is
     * never populated, then its address in the parent will remain NULL (0L) and
     * the leaf will not be emitted; (B) When one or more leaves is not emitted,
     * it may happen that the separator key was not assigned to the parent node
     * already having at least one child leaf. If this occurs, then we assign
     * successor(lastkey) as the successor key for that parent node; and (C) it
     * may happen that the root node is never populated. In such cases we use
     * the highest node whose separator key was assigned as the root (this
     * generalization covers all cases, including when the range count was
     * exact).
     * <p>
     * In order to support these changes to the build, the {@link AbstractBTree}
     * (or at least the {@link IndexSegment}) must allow the rightSibling of a
     * node having only a single key to be NULL (0L) and treat this as the right
     * edge of the BTree. In addition, the {@link AbstractBTree} (or at least
     * the {@link IndexSegment}) must allow a non-root leaf to underflow (down
     * to one tuple) and to allow a non-root node to underflow (down to one
     * separator key with no right child). These are all conditions which would
     * indicate an ill-formed B+Tree under normal circumstances, but which must
     * be explicitly allowed for an {@link IndexSegment} in order to support
     * builds based on overestimates of the range count.
     * 
     * FIXME Support underflow in the source tuple iterator.
     * 
     * @todo Verify correct rejection if the source iterator visits too many
     *       tuples (or right expand the output BTree to handle the actual
     *       tuples).
     */
    protected void buildBTree() {

//        // Flag used to flush the last leaf iff it is dirty.
//        boolean needsFlush = false;
        
        // For each leaf in the plan while tuples remain.
        for (int i = 0; i < plan.nleaves && entryIterator.hasNext(); i++) {

            /*
             * Fill in defined keys and values for this leaf.
             * 
             * Note: Since the shortage (if any) is distributed by the plan from
             * the last leaf backward a shortage will cause [leaf] to have
             * key/val data that is not overwritten. This does not cause a
             * problem as long as [leaf.nkeys] is set correctly since only that
             * many key/val entries will actually be serialized.
             */

            leaf.reset(plan.numInNode[leaf.level][i]);

            final int limit = leaf.max; // #of keys to fill in this leaf.

            // For each tuple allowed by the plan into the current leaf.
            for (int j = 0; j < limit && entryIterator.hasNext(); j++) {

                // Copy the tuple into the leaf.
                copyTuple(j, entryIterator.next());

//                needsFlush = true;
                
                if (i > 0 && j == 0) {

                    /*
                     * Every time (after the first) that we enter a new leaf we
                     * need to record its first key as a separatorKey in the
                     * appropriate parent.
                     * 
                     * Note: In the case where the parent of the previous leaf
                     * is full, this actually ascends through the parent of the
                     * previous leaf since the parent slot in the stack has not
                     * yet been reset. This can be a different node than the
                     * parent of this leaf, but only in the case when the parent
                     * of the previous leaf was full. In that case, the
                     * separatorKey is lifted into the parent's parent until an
                     * open slot is found. While confusing, the separatorKey
                     * always winds up in the correct node.
                     */

                    addSeparatorKey(leaf);

                }

            }

            /*
             * Close the current leaf. This will write the address of the leaf
             * on the parent (if any). If the parent becomes full then the
             * parent will be closed as well.
             */
            flushNodeOrLeaf(leaf, !entryIterator.hasNext());

//            needsFlush = false;

        }

//        if (needsFlush) {
//
//            /*
//             * This flushes the last leaf when the plan was based on an over
//             * estimate of the range count of the source iterator.
//             */
//
//            flush(leaf, true/* exhausted */);
//
//        }

    }
    
    /**
     * Copy a tuple into the current leaf at the given index.
     * 
     * @param j
     *            The index in the leaf to which the tuple will be copied.
     * @param tuple
     *            The tuple.
     */
    private void copyTuple(final int j, final ITuple<?> tuple) {

        if (ntuplesWritten == 0) {

            // Verify iterator is reporting necessary data.
            assertIteratorOk(tuple);
            
        }
            
        ntuplesWritten++;
            
        final MutableKeyBuffer keys = leaf.keys;
        
        assert keys.nkeys == j;
        
        keys.keys[j] = tuple.getKey();

        if (deleteMarkers)
            leaf.deleteMarkers[j] = tuple.isDeletedVersion();

        if (versionTimestamps) {
         
            final long t = tuple.getVersionTimestamp();
            
            leaf.versionTimestamps[j] = t; 

            if (t < leaf.minimumVersionTimestamp)
                leaf.minimumVersionTimestamp = t;

            if (t > leaf.maximumVersionTimestamp)
                leaf.maximumVersionTimestamp = t;
            
        }

        final byte[] val;

        if(deleteMarkers && tuple.isDeletedVersion()) {
            
            val = null;
            
        } else {

            if (overflowHandler != null) {

                /*
                 * Provide the handler with the opportunity to copy
                 * the blob's data onto the buffer and re-write the
                 * value, which is presumably the blob reference.
                 */

                val = overflowHandler.handle(tuple, blobBuffer);

            } else {

                val = tuple.getValue();

            }
        
        }
        
        leaf.vals.values[j] = val; 

        if (bloomFilter != null) {

            /*
             * Note: We record the keys for deleted tuples in the
             * bloom filter. This is important since we need a
             * search of an ordered set of AbstractBTree sources for
             * a FusedView to halt as soon as it finds a delete
             * marker for a key. If we do not add the key for
             * deleted tuples to the bloom filter then the bloom
             * filter will report (incorrectly) that the key is not
             * in this IndexSegment. It is - with a delete marker.
             */
            
            bloomFilter.add(keys.keys[j]);
            
        }
        
        keys.nkeys++;
        leaf.vals.nvalues++;

    }
    
    /**
     * This is invoked for the first tuple visited to make sure that the
     * iterator is reporting the data we need.
     */
    private void assertIteratorOk(final ITuple<?> tuple) {

        if (!tuple.getKeysRequested())
            throw new RuntimeException("keys not reported by itr.");

        if (!tuple.getValuesRequested())
            throw new RuntimeException("vals not reported by itr.");

        if (!compactingMerge && deleteMarkers
                && ((tuple.flags() & IRangeQuery.DELETED) == 0)) {

            /*
             * This is an incremental build and the source index supports delete
             * markers but the iterator is not visiting deleted tuples.
             */

            throw new RuntimeException("delete markers not reported by itr.");

        }

        /*
         * @todo I am not sure about this test. iterators should always report
         * the revision timestamp metadata. The real question is whether or not
         * they are reporting deleted tuples and that is tested above. [The
         * other question is whether we always need to report deleted tuples for
         * an isolatable index and that is what I am not sure about.]
         */
        assert !isolatable
                || (isolatable && ((tuple.flags() & IRangeQuery.DELETED) == 0))
                : "version metadata not reported by itr for isolatable index";

    }

    /**
     * Used to make sure that the output file is deleted unless it was
     * successfully processed.
     */
    private void deleteOutputFile() {

        if (out != null && out.getChannel().isOpen()) {
            
            try {
            
//                FileLockUtility.closeFile(outFile, out);
                 out.close();
                
            } catch (Throwable t) {

                log.error("Ignoring: " + t, t);

            }

        }

        if (!outFile.delete()) {

            log.warn("Could not delete: file=" + outFile.getAbsolutePath());

        }

        if (storeCache != null) {

            /*
             * Clear the cache since the index segment store was not generated
             * successfully and the cache records will never be read.
             */
            
            storeCache.clear();

        }

    }

    /**
     * <p>
     * Flush a node or leaf that has been closed (no more data will be added).
     * </p>
     * <p>
     * Note: When a node or leaf is flushed we write it out to obtain its
     * address and set that address on its direct parent using
     * {@link #addChild(SimpleNodeData, long, AbstractSimpleNodeData, boolean)}.
     * This also updates the per-child counters of the #of entries spanned by a
     * node.
     * </p>
     * 
     * @param node
     *            The node to be flushed.
     * 
     * @param exhausted
     *            If the source {@link #entryIterator} is exhausted.
     */
    protected void flushNodeOrLeaf(final AbstractSimpleNodeData node,
            final boolean exhausted) {

        final int h = node.level;

        // The index into the level for this node or leaf.
        final int col = writtenInLevel[h];

        assert col < plan.numInLevel[h];

        if (log.isDebugEnabled())
            log.debug("closing " + (node.isLeaf() ? "leaf" : "node") + "; h="
                    + h + ", col=" + col + ", max=" + node.max + ", nkeys="
                    + node.keys.size());

        /*
         * Note: Nodes are written out immediately. For a leaf, this allocates a
         * data record for the leaf and updates the last leaf's representation
         * to set the priorAddr and nextAddr fields. If the source iterator is
         * exhausted then the nextAddr field will remain 0L.
         * 
         * Note: This will recursively invoke flush() if the parent Node is
         * full.
         * 
         * Note: The node is not reset in the stack by this method so it will
         * remain available to getParent(), which we invoke next.
         */
        final long addr = writeNodeOrLeaf(node, exhausted);

        // Lookup the parent of this leaf/node in the stack.
        final SimpleNodeData parent = getParent(node);
        
        if(parent != null) {

            addChild(parent, addr, node, exhausted);

            // @todo assert weakened well-formedness module constraints.
            
        }

//        if (col + 1 < plan.numInLevel[h]) {
//
//            int max = plan.numInNode[h][col + 1];
//
//            parent.reset(max);
//
//        }

        writtenInLevel[h]++;        

    }

    /**
     * Record the persistent address of a child on its parent and the #of
     * entries spanned by that child. If all children on the parent become
     * assigned then the parent is closed.
     * 
     * @param parent
     *            The parent.
     * @param childAddr
     *            The address of the child (node or leaf).
     * @param child
     *            The child reference.
     * @param exhausted
     *            if the source {@link #entryIterator} is exhausted.
     */
    protected void addChild(final SimpleNodeData parent, final long childAddr,
            final AbstractSimpleNodeData child, final boolean exhausted) {

        // #of entries spanned by this node.
        final int nentries = child.getSpannedTupleCount();

        if (parent.nchildren == parent.max) {// && !exhausted) {

            /*
             * If there are more nodes to be filled at this level then prepare
             * this node to receive its next values/children.
             */
            
            resetNode(parent);
            
        }

        // assert parent.nchildren < parent.max;

        if(log.isDebugEnabled())
            log.debug("setting " + (child.isLeaf() ? "leaf" : "node")
                    + " as child(" + parent.nchildren + ")" + " at h="
                    + parent.level + ", col=" + writtenInLevel[parent.level]
                    + ", addr=" + addressManager.toString(childAddr));

        final int nchildren = parent.nchildren;
        
        parent.childAddr[nchildren] = childAddr;
        
        parent.childEntryCount[nchildren] = nentries;

        parent.nentries += nentries;
        
        if(versionTimestamps) {

            parent.minimumVersionTimestamp = Math.min(
                    parent.minimumVersionTimestamp,
                    child.minimumVersionTimestamp);

            parent.maximumVersionTimestamp = Math.max(
                    parent.maximumVersionTimestamp,
                    child.maximumVersionTimestamp);
            
        }
        
        parent.nchildren++;

        final int h = parent.level;
        if (exhausted
                && child.isLeaf()
//                && parent != null
                // #of separator keys LT planned childCount for parent.
                && (parent.keys.nkeys + 1) < plan.numInNode[h][writtenInLevel[h]]) {

            /*
             * When the source iterator is exhausted before the expected #of
             * tuples have been processed then the last leaf will be
             * non-empty (we do not start a leaf unless there is at least
             * one tuple on hand to copy into that leaf). Unless this is the
             * root leaf, then its parent may lack a separator key since the
             * separator key is chosen based on the first key to enter the
             * next leaf and we will never generate that next leaf since
             * there are no more tuples in the source iterator. This edge
             * case is detected when the #of children in the parent of the
             * last leaf is less than the #of planned children. Since we
             * never saw the next planned leaf, we need to hack in a
             * separator key for that leaf now so that queries LT the
             * separator key are directed to the last leaf which we did see.
             * This edge case is handled by adding a separatorKey based on
             * successor(lastKey) to the parent of the last leaf.
             */

            final byte[] lastKey = leaf.keys.keys[leaf.keys.nkeys - 1];

            final byte[] separatorKey = BytesUtil.successor(lastKey);

            parent.keys.keys[parent.keys.nkeys++] = separatorKey;
//            addSeparatorKey(parent, separatorKey);

            /*
             * @todo Note that the childAddr of the next leaf was already
             * assigned since we allocate the leaf's record before it is
             * populated, so we zero out that childAddr now. [The non-0L
             * childAddr for this last least is not really a problem since it
             * will never be visited by top-down navigation (the B+Tree will not
             * have any data for keys GTE the successor key directing probes to
             * that leaf). What is more important is that the
             * IndexSegmentCheckpoint should not direct us to the empty last
             * leaf and that the current leaf [node] should have nextAddr=0L so
             * we never navigate to that last leaf.
             * 
             * @todo Write more detailed unit tests for these points.
             */
//            parent.childAddr[parent.keys.nkeys] = 0L;

        }

        if (parent.nchildren == parent.max || exhausted) {

            /*
             * Flush the parent if the leaf/node is full or if the source
             * iterator is exhausted.
             */
            
            flushNodeOrLeaf(parent, exhausted);
            
        }
        
    }

    /**
     * The {@link #stack} contains nodes which are reused for each node or leaf
     * at a given level in the generated B+Tree. This method prepares a node in
     * the stack for reuse.
     */
    protected void resetNode(final SimpleNodeData parent) {

        if (log.isDebugEnabled())
            log.debug("resetting node: h=" + parent.level);
        
        final int h = parent.level;

        /*
         * The index into the level for this node. Note that we subtract one
         * since the node is full and was already "closed". What we are
         * trying to figure out here is whether the node may be reset so as
         * to allow more children into what is effectively a new node or
         * whether there are no more nodes allowed at this level of the
         * output tree.
         */
        final int col = writtenInLevel[h] - 1;

        if (col + 1 < plan.numInLevel[h]) {

            /*
             * Reset the Node in the stack. It will be reused for the next
             * Node at the same level in the B+Tree.
             */

            parent.reset(plan.numInNode[h][col + 1]/*max*/);

        } else {

            /*
             * The data is driving us to populate more nodes in this level
             * than the plan allows for the output tree. This is either an
             * error in the control logic or an error in the plan.
             */
            throw new AssertionError();

        }

    }
    
    /**
     * Copies the first key of a new leaf as a separatorKey for the appropriate
     * parent (if any) of that leaf. This must be invoked when the first key is
     * set on that leaf. However, it must not be invoked on the first leaf.
     * 
     * @param leaf
     *            The current leaf. The first key on that leaf must be defined.
     */
    protected void addSeparatorKey(final SimpleLeafData leaf) {
        
        final SimpleNodeData parent = getParent(leaf);

        if (parent == null) {

            /*
             * This is the root leaf, so there is no parent and the separator
             * key will not be assigned.
             */
       
            return;

        }

        /*
         * @todo Use the shortest separator key (this provides space savings on
         * the nodes, but prefix compression of the keys has much the same
         * effect).
         */

        final byte[] separatorKey = leaf.keys.get(0);

        if (separatorKey == null) {

            throw new AssertionError();

        }

        addSeparatorKey(parent, separatorKey);

    }

    /**
     * Copies the separatorKey into the appropriate parent (if any). This method
     * is self-recursive.
     * 
     * @param parent
     *            A node which is a parent of the current leaf or an ancestor of
     *            the node which is the parent of the current leaf (non-null).
     * @param separatorKey
     *            The separator key to be assigned to the parent (non-null).
     */
    private void addSeparatorKey(final SimpleNodeData parent,
            final byte[] separatorKey) {

        if (parent == null)
            throw new AssertionError();
        
        if (separatorKey == null)
            throw new AssertionError();

        /*
         * The maximum #of keys for a node is one less key than the maximum #of
         * children for that node.
         */
        final int maxKeys = parent.max - 1;

        final MutableKeyBuffer parentKeys = parent.keys;

        if (parentKeys.nkeys < maxKeys) {

            /*
             * Copy the separator key into the next free position on the parent,
             * incrementing the #of keys in the parent.
             */

            if (log.isDebugEnabled())
                log.debug("h=" + parent.level + ", col="
                        + writtenInLevel[parent.level] + ", separatorKey="
                        + BytesUtil.toString(separatorKey));

            parentKeys.keys[parentKeys.nkeys++] = separatorKey;
//            parentKeys.keys[parentKeys.nkeys++] = leaf.keys.get(0);
//            parent.copyKey(parentKeys.nkeys++, leaf, 0 );

        } else {

            /*
             * Delegate to the parent recursively until we find the first parent
             * into which the separatorKey can be inserted.
             */

            addSeparatorKey(getParent(parent), separatorKey);

        }
        
    }

    /**
     * Return the parent of a node or leaf in the {@link #stack}.
     * 
     * @param node
     *            The node or leaf.
     * 
     * @return The parent or <code>null</code> iff <i>node</i> is the root node
     *         or leaf.
     */
    protected SimpleNodeData getParent(final AbstractSimpleNodeData node) {
        
        if (node.level == 0) {
        
            return null;
            
        }

        return (SimpleNodeData) stack[node.level - 1];

    }

    /**
     * Write the node or leaf onto the appropriate output channel.
     * 
     * @return The address that may be used to read the node or leaf from the
     *         file. Note that the address of a node is relative to the start of
     *         the node channel and therefore must be adjusted before reading
     *         the node from the final index segment file.
     */
    protected long writeNodeOrLeaf(final AbstractSimpleNodeData node,
            final boolean exhausted)    {
        
        return node.isLeaf() ? writeLeaf((SimpleLeafData) node, exhausted)
                : writeNode((SimpleNodeData) node, exhausted);
        
    }

    /**
     * Code the leaf onto the {@link #leafBuffer}, obtaining its address.
     * <p>
     * Note: For leaf addresses we know the absolute offset into the
     * {@link IndexSegmentStore} where the leaf will wind up so we encode the
     * address of the leaf using the {@link IndexSegmentRegion#BASE} region.
     * <p>
     * Note: In order to write out the leaves using a double-linked list with
     * prior-/next-leaf addresses we have to use a "write behind" strategy.
     * Instead of writing out the leaf as soon as it is serialized, we save the
     * uncoded address and a copy of the coded data record on private member
     * fields. When we code the next leaf (or if we learn that we have no more
     * leaves to code because {@link IndexSegmentPlan#nleaves} EQ
     * {@link #nleavesWritten} or because <code>exhausted == false</code>) then
     * we patch the coded representation of the prior leaf and write it on the
     * store at the previously obtained address, thereby linking the leaves
     * together in both directions. It is definitely confusing.
     * 
     * @return The address that may be used to read the leaf from the file
     *         backing the {@link IndexSegmentStore}.
     */
    protected long writeLeaf(final SimpleLeafData leaf, final boolean exhausted) {

        /*
         * The encoded address of the leaf that we allocated here. The encoded
         * address will be relative to the BASE region.
         */ 
        final long addr;
        {
            
            // code the leaf, obtaining a view onto an internal (shared) buffer.
//            final ByteBuffer buf = nodeSer.encode(leaf).asByteBuffer();
            // code the leaf.
            final ILeafData thisLeafData = nodeSer.encodeLive(leaf);

            // Allocate a record for the leaf on the temporary store.
//            final long addr1 = leafBuffer.allocate(buf.remaining());
            final long addr1 = leafBuffer.allocate(thisLeafData.data().len());
            
            // encode the address assigned to the serialized leaf.
            addr = encodeLeafAddr(addr1);
            
            if (log.isDebugEnabled())
                log.debug("allocated storage for leaf data record"//
                        + ": addr=" + addressManager.toString(addr));

            if (nleavesWritten > 0) {
                
                /*
                 * Update the previous leaf, but only for the 2nd+ leaf.
                 */

                if (log.isDebugEnabled())
                    log.debug("updating previous leaf"//
                            + ": addr="+addressManager.toString(encodeLeafAddr(bufLastLeafAddr))//
                            + ", priorAddr="+ addressManager.toString(addrPriorLeaf)//
                            + ", nextAddr=" + addressManager.toString(addr)//
                            + ", exhausted=" + exhausted
                            );
                else if (log.isInfoEnabled())
                    System.err.print("."); // wrote a leaf.

                // view onto the coded record for the prior leaf.
                final ByteBuffer bufLastLeaf = lastLeafData.data().asByteBuffer();

                /*
                 * Patch representation of the previous leaf.
                 * 
                 * Note: This patches the coded record using the ByteBuffer view
                 * of that record. However, the change is made to the backing
                 * byte[] so the change is visible on the coded record as well.
                 */
                nodeSer.updateLeaf(bufLastLeaf, addrPriorLeaf, addr/*addrNextLeaf*/);
                assert lastLeafData.getPriorAddr() == addrPriorLeaf;
                assert lastLeafData.getNextAddr() == addr;

                // write the previous leaf onto the store.
                leafBuffer.update(bufLastLeafAddr, 0/*offset*/, bufLastLeaf);
                
                // the encoded address of the leaf that we just wrote out.
                addrPriorLeaf = encodeLeafAddr(bufLastLeafAddr);

                if (storeCache != null) {

                    /*
                     * Insert the coded, patched record for the prior leaf into
                     * cache.
                     */

                    storeCache.putIfAbsent(addrPriorLeaf, lastLeafData);
                    
                }
                
            }
            
            // update reference to the leaf we just coded.
            lastLeafData = thisLeafData;
            
            // the address allocated for the leaf in the temp store.
            bufLastLeafAddr = addr1;
            
        }

        if (nleavesWritten == 0) {

            /*
             * Encoded addr of the 1st leaf - update only for the first leaf
             * that we allocate.
             */
            addrFirstLeaf = addr;
            
        }
        
        // encoded addr of the last leaf - update for each leaf that we allocate.
        addrLastLeaf = addr;
        
        // the #of leaves written so far.
        nleavesWritten++;

        if (/*plan.nleaves == nleavesWritten ||*/ exhausted) {

            /*
             * Update the last leaf.
             * 
             * Note: The last leaf is the one for which we allocated storage
             * immediately above.
             * 
             * Note: We must force out the last leaf when the source iterator is
             * exhausted since the plan could have been generated from an
             * overestimate of the range count.
             * 
             * Note: We only invoke flush() if a leaf has data so we should
             * never be in a position of writing out an empty leaf.
             */
            assert lastLeafData.getKeyCount() > 0 : "Last leaf is empty?";

            if (log.isDebugEnabled())
                log.debug("updating last leaf"//
                        + ": addr="+addressManager.toString(encodeLeafAddr(bufLastLeafAddr))//
                        + ", priorAddr="+ addressManager.toString(addrPriorLeaf)//
                        + ", nextAddr=0L"//
                        + ", exhausted="+exhausted
                        );
//                log.debug("Writing leaf: priorLeaf=" + addrPriorLeaf
//                        + ", nextLeaf=" + 0L + ", exhausted=" + exhausted);
            else if (log.isInfoEnabled())
                System.err.print("."); // wrote a leaf.

            // View onto the coded record for the prior leaf.
            final ByteBuffer bufLastLeaf = lastLeafData.data().asByteBuffer();

            /*
             * Patch representation of the last leaf.
             * 
             * Note: This patches the coded record using the ByteBuffer view
             * of that record. However, the change is made to the backing
             * byte[] so the change is visible on the coded record as well.
             */
            nodeSer.updateLeaf(bufLastLeaf, addrPriorLeaf, 0L/*addrNextLeaf*/);
            assert lastLeafData.getPriorAddr() == addrPriorLeaf;
            assert lastLeafData.getNextAddr() == 0L;

            // write the last leaf onto the store.
            leafBuffer.update(bufLastLeafAddr, 0/*offset*/, bufLastLeaf);

            if (storeCache != null) {

                /*
                 * Insert the coded, patched record for the prior leaf into
                 * cache.
                 */

                storeCache.putIfAbsent(addrLastLeaf, lastLeafData);
                
            }
            
        }

        return addr;
        
    }

    /**
     * Encode the address of a leaf.
     * <p>
     * Note: This updates {@link #maxNodeOrLeafLength} as a side-effect.
     * 
     * @param addr1
     *            The address of a leaf as allocated by the {@link #leafBuffer}
     * 
     * @return The encoded address of the leaf relative to the
     *         {@link IndexSegmentRegion#BASE} region where it will appear once
     *         the leaves have been copied onto the output file.
     */
    private long encodeLeafAddr(final long addr1) {

        final int nbytes = addressManager.getByteCount(addr1);

        if (nbytes > maxNodeOrLeafLength) {

            // track the largest node or leaf written.
            maxNodeOrLeafLength = nbytes;

        }

        /*
         * Note: The offset is adjusted by the size of the checkpoint record
         * such that the offset is correct for the generated file NOT the buffer
         * into which the leaves are being written.
         */
        final long offset = addressManager.getOffset(addr1)
                + IndexSegmentCheckpoint.SIZE;
        
        // Encode the address of the leaf.
        final long addr = addressManager.toAddr(nbytes, IndexSegmentRegion.BASE
                .encodeOffset(offset));
        
        return addr;

    }

    /*
     * Data used to chain the leaves together in a prior/next double-linked
     * list.
     */

    /**
     * The address of the previous leaf written on the {@link #leafBuffer}, but
     * encoded for the generated {@link IndexSegmentStore}.
     */
    private long addrPriorLeaf = 0L;
    
    /**
     * The address of the last leaf allocated (but not yet written) on the
     * {@link #leafBuffer} (the {@link TemporaryRawStore} on which the leaves
     * are buffered).
     * <p>
     * Note: This address is NOT encoded for the {@link IndexSegmentStore}. It
     * is used to specify which record we want to update the record on the
     * {@link #leafBuffer} using
     * {@link TemporaryRawStore#update(long, int, ByteBuffer)}.
     * <p>
     * Note: This is used to patch the representation of the last serialized
     * leaf in {@link #bufLastLeaf} with the address of the next leaf in key
     * order.
     * 
     * @see #writeLeaf(SimpleLeafData)
     * @see #writePriorLeaf(long nextLeafAddr)
     */
    private long bufLastLeafAddr = 0L;
    
//    /**
//     * Buffer holds a copy of the serialized representation of the last leaf.
//     * This buffer is reset and written by {@link #writeLeaf(SimpleLeafData)}.
//     * The contents of this buffer are used by {@link #writePriorLeaf(long)} to
//     * write out the serialized representation of the previous leaf in key order
//     * after it has been patched to reflect the prior and next leaf addresses.
//     * The buffer is automatically reallocated if it is too small for a leaf.
//     */
//    private ByteBuffer bufLastLeaf = ByteBuffer.allocate(10 * Bytes.kilobyte32);
    /**
     * Buffer holds a copy of the coded representation of the last leaf. This
     * buffer is written by {@link #writeLeaf(SimpleLeafData)}. The contents of
     * this buffer are used to write out the serialized representation of the
     * previous leaf in key order after it has been patched to reflect the prior
     * and next leaf addresses. The coded {@link ILeafData} record is modified
     * before the previous leaf is written out to reflect the address assigned
     * to the next leaf in key order.
     */
    private ILeafData lastLeafData;
    
    /**
     * Code and write the node onto the {@link #nodeBuffer}.
     * 
     * @return An <em>relative</em> address that must be correctly decoded
     *         before you can read the compressed node from the file. This value
     *         is also set on {@link SimpleNodeData#addr}.
     * 
     * @see SimpleNodeData
     * @see IndexSegmentRegion
     * @see IndexSegmentAddressManager
     */
    protected long writeNode(final SimpleNodeData node, final boolean exhausted) {

        // code node, obtaining slice onto shared buffer and wrap that
        // shared buffer.
        final INodeData codedNodeData = nodeSer.encodeLive(node);
//        final ByteBuffer buf = nodeSer.encode(node).asByteBuffer();

        // write the node on the buffer (a temporary store).
//        final long tempAddr = nodeBuffer.write(buf);
        final long tempAddr = nodeBuffer.write(codedNodeData.data().asByteBuffer());

        final long offset = addressManager.getOffset(tempAddr);
        
        final int nbytes = addressManager.getByteCount(tempAddr);
        
        if( nbytes > maxNodeOrLeafLength ) { 
         
            // track the largest node or leaf written.
            maxNodeOrLeafLength = nbytes;
            
        }

        // the #of nodes written so far.
        nnodesWritten++;

        if (log.isInfoEnabled())
            System.err.print("x"); // wrote a node.

        /*
         * Encode the node address. Since we do not know the offset of the NODE
         * region in advance this address gets encoded as relative to the start
         * of the NODE region in the file.
         */
        final long addr = addressManager.toAddr(nbytes, IndexSegmentRegion.NODE
                .encodeOffset(offset));
        
        node.addr = addr;
        
        if (storeCache != null) {

            /*
             * Insert the coded record into cache as [addr2 : nodeData], where
             * nodeData is encodeLive() wrapped version of the slice.
             */
            
            storeCache.putIfAbsent(addr, codedNodeData);
            
        }
        
        return addr;
        
    }

    /**
     * <p>
     * Writes the complete file format for the index segment. The file is
     * divided up as follows:
     * <ol>
     * 
     * <li>fixed length {@link IndexSegmentCheckpoint} record (required)</li>
     * <li>leaves (required)</li>
     * <li>nodes (may be empty)</li>
     * <li>the bloom filter (optional)</li>
     * <li>the {@link IndexMetadata} record (required, but extensible)</li>
     * </ol>
     * </p>
     * <p>
     * The index segment metadata is divided into a base
     * {@link IndexSegmentCheckpoint} record with a fixed format containing only
     * essential data and additional metadata records written at the end of the
     * file including the optional bloom filter and the required
     * {@link IndexMetadata} record. The latter is where we write variable
     * length metadata including the _name_ of the index, or additional metadata
     * defined by a specific class of index.
     * </p>
     * <p>
     * Once all nodes and leaves have been buffered we are ready to start
     * writing the data. We skip over a fixed size metadata record since
     * otherwise we are unable to pre-compute the offset to the leaves and hence
     * the addresses of the leaves. The node addresses are written in an
     * encoding that requires active translation by the receiver who must be
     * aware of the offset to the start of the node region. We can not write the
     * metadata record until we know the size and length of each of these
     * regions (leaves, nodes, and the bloom filter, or other metadata records)
     * since that information is required in order to be able to form their
     * addresses for insertion in the metadata record.
     * </p>
     * 
     * @param outChannel
     * 
     * @param commitTime
     * 
     * @throws IOException
     */
    protected IndexSegmentCheckpoint writeIndexSegment(
            final FileChannel outChannel, final long commitTime)
            throws IOException {

        /*
         * All nodes and leaves have been written. If we wrote any nodes
         * onto the temporary channel then we also have to bulk copy them
         * into the output channel.
         */
        final long offsetLeaves;
        final long extentLeaves;
        final long offsetNodes;
        final long extentNodes;
        final long offsetBlobs;
        final long extentBlobs;
        final long addrRoot;

        /*
         * Skip over the checkpoint record at the start of the file.
         * 
         * Note: We fill this areas with zeros. When the index segment is empty
         * (has no entries) then this causes the file length to be extended
         * beyond the checkpoint record and the index metadata record gets
         * written onto the file at that point. If we merely position the file
         * to beyond the checkpoint record then nothing has been written on the
         * file and the index metadata record gets written at offset 0L!
         */
        outChannel.write(ByteBuffer.allocate(IndexSegmentCheckpoint.SIZE));

        /*
         * Direct copy the leaves from their buffer into the output file. If the
         * buffer was backed by a file then that file will be deleted as a
         * post-condition on the index build operation.
         */
        if (leafBuffer == null) {

            /*
             * The tree is empty (no root leaf).
             */

            // No leaves.
            offsetLeaves = 0L;
            extentLeaves = 0L;

            // No nodes.
            offsetNodes = 0L;
            extentNodes = 0L;

            // No root.
            addrRoot = 0L;

        } else {

            offsetLeaves = IndexSegmentCheckpoint.SIZE;
            
            // output the leaf buffer.
            {

                /*
                 * Transfer the leaf buffer en mass onto the output channel.
                 * 
                 * Note: If a planned leaf is not emitted then this can cause an
                 * exception to be thrown indicating that the IO transfer is not
                 * progressing. This occurs when the record for that leaf was
                 * allocated on the leafBuffer but never written onto the
                 * leafBuffer. This allocate-then-write policy allows us to
                 * double-link the leaves during the build. The build SHOULD
                 * automatically correct for cases when there are not enough
                 * tuples to fill out the leaves in the plan. However, if it
                 * does not correct the problem, and hence does not write the
                 * last allocated leaf data record, then you might see this
                 * exception.
                 */
                extentLeaves = leafBuffer.getBufferStrategy().transferTo(out);

                if (nodeBuffer != null) {

                    // The offset to the start of the node region.
                    offsetNodes = IndexSegmentCheckpoint.SIZE + extentLeaves;

                    assert outChannel.position() == offsetNodes;

                } else {

                    // zero iff there are no nodes.
                    offsetNodes = 0L;

                }

                // Close the buffer.
                leafBuffer.close();

            }

            /*
             * Direct copy the node index from the buffer into the output file.
             * If the buffer was backed by a file then that file will be deleted
             * as a post-condition on the index build operation.
             */
            if (nodeBuffer != null) {

                // transfer the nodes en mass onto the output channel.
                extentNodes = nodeBuffer.getBufferStrategy().transferTo(out);

                // Close the buffer.
                nodeBuffer.close();

                // Note: already encoded relative to NODE region.
                // FIXME Use the highest node whose separator key was assigned.
                addrRoot = (((SimpleNodeData) stack[0]).addr);

            } else {

                /*
                 * The tree consists of just a root leaf.
                 */

                // This MUST be 0L if there are no leaves.
                extentNodes = 0L;

                // Address of the root leaf.
                addrRoot = addrLastLeaf;

            }

        }

        if (log.isInfoEnabled())
            log.info("addrRoot: " + addrRoot + ", "
                    + addressManager.toString(addrRoot));

        /*
         * Direct copy the optional blobBuffer onto the output file.
         */
        if (blobBuffer == null) {

            // No blobs region.
            offsetBlobs = extentBlobs = 0L;

        } else {
            
            // #of bytes written so far on the output file.
            offsetBlobs = out.length(); 

            // seek to the end of the file.
            out.seek(offsetBlobs);

            // transfer the nodes en mass onto the output channel.
            extentBlobs = blobBuffer.getBufferStrategy().transferTo(out);
            
            // Close the buffer.
            blobBuffer.close();

        }
        
        /*
         * If the bloom filter was constructed then serialize it on the end
         * of the file.
         */
        final long addrBloom;
        
        if( bloomFilter == null ) {
    
            addrBloom = 0L;
            
        } else {

            // serialize the bloom filter.
            final byte[] bloomBytes = SerializerUtil.serialize(bloomFilter);

            // #of bytes written so far on the output file.
            final long offset = out.length(); 

            // seek to the end of the file.
            out.seek(offset);
            
            // write the serialized bloom filter.
            out.write(bloomBytes, 0, bloomBytes.length);
            
            // Address of the region containing the bloom filter (one record).
            addrBloom = addressManager.toAddr(bloomBytes.length,
                    IndexSegmentRegion.BASE.encodeOffset(offset));
                         
            if (storeCache != null) {

                /*
                 * Insert the record into the cache.
                 */
                
                storeCache.putIfAbsent(addrBloom, bloomFilter);
                
            }
            
        }
        
        /*
         * Write out the metadata record.
         */
        final long addrMetadata;
        {

            /*
             * Serialize the metadata record.
             */
            final byte[] metadataBytes = SerializerUtil.serialize(metadata);

            // #of bytes written so far on the output file.
            final long offset = out.length(); 

            // seek to the end of the file.
            out.seek(offset);
            
            // write the serialized extension metadata.
            out.write(metadataBytes, 0, metadataBytes.length);

            // Address of the region containing the metadata record (one record)
            addrMetadata = addressManager.toAddr(metadataBytes.length,
                    IndexSegmentRegion.BASE.encodeOffset(offset));
            
            if (storeCache != null) {

                /*
                 * Insert the record into the cache.
                 */
                
                storeCache.putIfAbsent(addrMetadata, metadata);
                
            }

        }
        
        /*
         * Seek to the start of the file and write out the checkpoint record.
         */
        {

//            // timestamp for the index segment.
//            final long now = System.currentTimeMillis();
            
            outChannel.position(0);

            /*
             * Note: The checkpoint needs to report the actual #of leaves,
             * nodes, and tuples written -- not those in the plan since the plan
             * can report more of each when the given range count is
             * overestimates the actual #of tuples copied into the index
             * segment.
             */
            final IndexSegmentCheckpoint md = new IndexSegmentCheckpoint(
                    addressManager.getOffsetBits(), //
                    plan.height, // will always be correct.
                    nleavesWritten, // actual #of leaves written.
                    nnodesWritten, // actual #of nodes written.
                    ntuplesWritten, // actual #of tuples written.
                    maxNodeOrLeafLength,//
                    offsetLeaves, extentLeaves, offsetNodes, extentNodes,
                    offsetBlobs, extentBlobs, addrRoot, addrMetadata,
                    addrBloom, addrFirstLeaf, addrLastLeaf, out.length(),
                    compactingMerge, segmentUUID, commitTime);

            md.write(out);
            
            if(log.isInfoEnabled())
                log.info(md.toString());

            // save the index segment resource description for the caller.
            this.segmentMetadata = new SegmentMetadata(outFile, //out.length(),
                    segmentUUID, commitTime);
            
            return md;
            
        }

    }

    /**
     * The description of the constructed {@link IndexSegment} resource.
     * 
     * @throws IllegalStateException
     *             if requested before the build operation is complete.
     */
    public IResourceMetadata getSegmentMetadata() {
        
        if (segmentMetadata == null) {

            throw new IllegalStateException();
            
        }
        
        return segmentMetadata;
        
    }
    private SegmentMetadata segmentMetadata = null;

    /**
     * Abstract base class for classes used to construct and serialize nodes and
     * leaves written onto the index segment.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan
     *         Thompson</a>
     * @version $Id$
     */
    abstract protected static class AbstractSimpleNodeData implements
            IAbstractNodeData {

        /**
         * The level in the output tree for this node or leaf (origin zero). The
         * root is always at level zero (0).
         */
        final int level;
        final int m;

        /**
         * Mutable keys (directly managed by the {@link IndexSegmentBuilder}).
         */
        MutableKeyBuffer keys;

        /**
         * The max/max version timestamp for the node/leaf. These data are only
         * used when the B+Tree is maintaining per tuple revision timestamps.
         */
        long minimumVersionTimestamp;
        long maximumVersionTimestamp;
        
        /**
         * We precompute the #of children to be assigned to each node and the
         * #of values to be assigned to each leaf and store that value in this
         * field. While the field name is "max", this is the exact that must be
         * assigned to the node.
         */
        int max = -1;

        protected AbstractSimpleNodeData(final int level, final int m) {

            this.level = level;
            
            this.m = m;
            
            /*
             * @todo This should probably be dimensioned to m-1 for a node and m
             * for a leaf. The mutable B+Tree would have dimensions to m for a
             * node and m+1 for a leaf to allow for overflow during split/join,
             * but we only need the exact number of slots.
             */
            this.keys = new MutableKeyBuffer(m);
            
            this.minimumVersionTimestamp = Long.MAX_VALUE;
            
            this.maximumVersionTimestamp = Long.MIN_VALUE;

        }

        /**
         * 
         * @param max
         *            The #of children to be assigned to this node -or- the #of
         *            tuples to be assigned to a leaf.
         */
        protected void reset(final int max) {
            
            this.max = max;
            
            this.keys.nkeys = 0;
            
            this.minimumVersionTimestamp = Long.MAX_VALUE;
            
            this.maximumVersionTimestamp = Long.MIN_VALUE;
            
        }
        
        final public int getKeyCount() {

            return keys.size();
            
        }

        final public IRaba getKeys() {

            return keys;
            
        }

        /**
         * Yes (however, note that the {@link IndexSegmentBuilder} directly
         * accesses and modified the internal data structures).
         */
        final public boolean isReadOnly() {
            
            return true;
            
        }
        
        /**
         * No.
         */
        final public boolean isCoded() {
            
            return false;
            
        }
        
        final public AbstractFixedByteArrayBuffer data() {
            
            throw new UnsupportedOperationException();
            
        }

        final public long getMaximumVersionTimestamp() {
            
            if(!hasVersionTimestamps())
                throw new UnsupportedOperationException();
            
            return minimumVersionTimestamp;
            
        }

        final public long getMinimumVersionTimestamp() {
         
            if(!hasVersionTimestamps())
                throw new UnsupportedOperationException();
            
            return maximumVersionTimestamp;
         
        }

    }
    
    /**
     * A class that can be used to (de-)serialize the data for a leaf without
     * any of the logic for operations on the leaf.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    protected static class SimpleLeafData extends AbstractSimpleNodeData
            implements ILeafData {

        /**
         * The values stored in the leaf (directly accessed by the
         * {@link IndexSegmentBuilder}).
         */
        final MutableValueBuffer vals;
        
        final public IRaba getValues() {
            
            return vals;
            
        }
        
        /**
         * Allocated iff delete markers are maintained.
         */
        final boolean[] deleteMarkers;
        
        /**
         * Allocated iff version timestamps are maintained.
         */
        final long[] versionTimestamps;

        public SimpleLeafData(final int level, final int m,
                final IndexMetadata metadata) {

            super(level, m);

            this.vals = new MutableValueBuffer(m);

            this.deleteMarkers = metadata.getDeleteMarkers() ? new boolean[m]
                    : null;

            this.versionTimestamps = metadata.getVersionTimestamps() ? new long[m]
                    : null;
            
        }
        
        protected void reset(final int max) {

            super.reset(max);

            vals.nvalues = 0;
            
        }
        
        final public int getSpannedTupleCount() {
            
            return keys.size();
            
        }

        final public int getValueCount() {
            
            return keys.size();
            
        }

        final public boolean isLeaf() {
            
            return true;
            
        }

        final public boolean getDeleteMarker(final int index) {

            if (deleteMarkers == null)
                throw new UnsupportedOperationException();

            return deleteMarkers[index];

        }

        final public long getVersionTimestamp(final int index) {

            if (versionTimestamps == null)
                throw new UnsupportedOperationException();

            return versionTimestamps[index];

        }

        final public boolean hasDeleteMarkers() {

            return deleteMarkers != null;

        }

        final public boolean hasVersionTimestamps() {

            return versionTimestamps != null;

        }

        /**
         * Yes - the caller maintains the necessary information and then updates
         * the coded {@link ReadOnlyLeafData} record once we have the address of
         * the next record.
         */
        final public boolean isDoubleLinked() {
            
            return true;
            
        }

        /**
         * @throws UnsupportedOperationException
         *             since the data are maintained externally and patched on
         *             the coded records by the {@link IndexSegmentBuilder}.
         */
        final public long getNextAddr() {
            
            throw new UnsupportedOperationException();
            
        }

        /**
         * @throws UnsupportedOperationException
         *             since the data are maintained externally and patched on
         *             the coded records by the {@link IndexSegmentBuilder}.
         */
        final public long getPriorAddr() {
            
            throw new UnsupportedOperationException();
            
        }

    }

    /**
     * A class that can be used to (de-)serialize the data for a node without
     * any of the logic for operations on the node.
     * <p>
     * Note: All node addresses that are internal to a node and reference a
     * child node (vs a leaf) are correct relative to the start of the
     * {@link IndexSegmentRegion#NODE} region. This is an unavoidable
     * consequence of serializing the nodes before we have the total offset to
     * the start of the {@link IndexSegmentRegion#NODE} region.
     * 
     * @see IndexSegmentRegion
     * @see IndexSegmentAddressManager
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    protected static class SimpleNodeData extends AbstractSimpleNodeData
            implements INodeData {

        // mutable.
        
        /**
         * The relative address at which this node was written on the temporary
         * channel. This is a negative integer. If you flip the sign then it
         * encodes a relative offset to the start of the index node block and
         * the correct size for the compressed node.
         */
        long addr = 0L;
        
        /**
         * The address at which the child nodes were written. This is a negative
         * integer iff the child is a node and a positive integer iff the child
         * is a leaf. When it is a negative integer, you must flip the sign to
         * obtain a relative offset to the start of the index node block and the
         * correct size for the compressed node. The actual offset of the index
         * node block must be added to the relative offset before you can use
         * this to address into the output file.
         */
        final long[] childAddr;
        
        /**
         * This tracks the #of defined values in {@link #childAddr} separately
         * from the #of defined keys. The reason that we do this is that the
         * logic for handling a leaf eviction and recording the address of the
         * child and the separator key for the _next_ child requires an
         * awareness of the intermediate state - when we have filled in the
         * childAddr for the last leaf but not yet filled in the separatorKey
         * for the next leaf.
         */
        int nchildren = 0;
        
        /**
         * The #of entries spanned by this node.
         */
        int nentries;
        
        /**
         * The #of entries spanned by each child of this node.
         */
        final int[] childEntryCount;

        /**
         * <code>true</code> iff the node is tracking the min/max tuple revision
         * timestamps.
         */
        final boolean hasVersionTimestamps;
        
        final public int getSpannedTupleCount() {
            
            return nentries;
            
        }

        final public long getChildAddr(final int index) {

            if (index < 0 || index > keys.size() + 1)
                throw new IllegalArgumentException();

            return childAddr[index];
            
        }

        final public int getChildEntryCount(final int index) {

            if (index < 0 || index > keys.size() + 1)
                throw new IllegalArgumentException();

            return childEntryCount[index];
            
        }

        public SimpleNodeData(final int level, final int m,
                final boolean hasVersionTimestamps) {

            super(level, m);

            this.childAddr = new long[m];
            
            this.childEntryCount = new int[m];
            
            this.hasVersionTimestamps = hasVersionTimestamps;
            
        }
        
        /**
         * Reset counters and flags so that the node may be reused.
         * 
         * @param max
         *            The new limit on the #of children to fill on this node.
         */
        protected void reset(final int max) {

            /*
             * Note: We have to clear these arrays for the edge case when source
             * iterator is prematurely exhausted. If we do not clear them then
             * the last entry in each array can be non-zero when it should be 0L
             * when the planned right child under a separatorKey in a Node was
             * not emitted.
             */
            for (int i = 0; i < nchildren; i++) {

                childAddr[i] = 0L;
                
                childEntryCount[i] = 0;
                
            }
                
            super.reset(max);
            
            addr = 0;
            
            nchildren = 0;

            nentries = 0;
            
        }

        final public int getChildCount() {

            return keys.size() + 1;
            
        }

        final public boolean isLeaf() {
            
            return false;
            
        }

        final public boolean hasVersionTimestamps() {
            
            return hasVersionTimestamps;
            
        }
        
    }

    /**
     * Factory does not support node or leaf creation.
     */
    protected static class NOPNodeFactory implements INodeFactory {

        public static final INodeFactory INSTANCE = new NOPNodeFactory();

        private NOPNodeFactory() {
        }

        public Leaf allocLeaf(final AbstractBTree btree, final long addr,
                final ILeafData data) {

            throw new UnsupportedOperationException();

        }

        public Node allocNode(final AbstractBTree btree, final long addr,
                final INodeData data) {

            throw new UnsupportedOperationException();

        }

    }

}
