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
package com.bigdata.journal.jini.ha;

import net.jini.config.Configuration;

/**
 * Test suite for HA3 with concurrent writers.
 * <p>
 * Note: A different code path is used for commit for HA1 than HA3 (no call to
 * postCommit() or postHACommit(). Thus some kinds of errors will only be
 * observable in HA3. See #1136.
 * 
 * TODO Do concurrent create / drop KB stress test with small loads in each KB.
 * 
 * TODO Do concurrent writers on the same KB. The operations should be
 * serialized (but if we do this from a pool of client threads then the N
 * updates can be melded into fewer than N commit points).
 * 
 * TODO Do concurrent writer use cases for concurrent writers that eventually
 * cause leader or follower fails to make sure that error recovery is Ok with
 * concurrent writers.
 * 
 * @see TestHA1GroupCommit
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */
public class TestHA3GroupCommit extends AbstractHAGroupCommitTestCase {

   /**
    * {@inheritDoc}
    * <p>
    * Note: This overrides some {@link Configuration} values for the
    * {@link HAJournalServer} in order to establish conditions suitable for
    * testing the {@link ISnapshotPolicy} and {@link IRestorePolicy}.
    */
   @Override
   protected String[] getOverrides() {
       
       return new String[]{
//            "com.bigdata.journal.HAJournal.properties=" +TestHA3JournalServer.getTestHAJournalProperties(com.bigdata.journal.HAJournal.properties),
               "com.bigdata.journal.jini.ha.HAJournalServer.restorePolicy=new com.bigdata.journal.jini.ha.DefaultRestorePolicy(0L,1,0)",
               "com.bigdata.journal.jini.ha.HAJournalServer.snapshotPolicy=new com.bigdata.journal.jini.ha.NoSnapshotPolicy()",
//               "com.bigdata.journal.jini.ha.HAJournalServer.HAJournalClass=\""+HAJournalTest.class.getName()+"\"",
               "com.bigdata.journal.jini.ha.HAJournalServer.onlineDisasterRecovery=true",
       };
       
   }
    
    public TestHA3GroupCommit() {
    }

    public TestHA3GroupCommit(String name) {
        super(name);
    }

    /**
     * Create 2 namespaces and then load data into those namespaces in parallel.
     * 
     * @throws Exception
     */
    public void test_HA3_GroupCommit_2Namespaces_ConcurrentWriters() throws Exception {

       new ABC(false/*sequential*/); // simultaneous start.

       doGroupCommit_2Namespaces_ConcurrentWriters(false/* reallyLargeLoad */);
       
    }

    /**
     * Create 2 namespaces and then load a large amount data into those namespaces in parallel.
     * 
     * @throws Exception
     */
    public void test_HA3_GroupCommit_2Namespaces_ConcurrentWriters_LargeLoad() throws Exception {

       new ABC(false/*sequential*/); // simultaneous start.

       doGroupCommit_2Namespaces_ConcurrentWriters(true/* reallyLargeLoad */);
       
    }

   /**
    * Create 2 namespaces and then load data into those namespaces in parallel
    * using a "DROP ALL; LOAD" pattern. A set of such tasks are generated and
    * the submitted in parallel. LOADs into the same namespace will be
    * serialized by the backend. Loads into different namespaces will be
    * parallelized.
    * 
    * @throws Exception
    */
   public void test_HA3_groupCommit_create2Namespaces_manyConcurrentLoadWithDropAll()
         throws Exception {

      final int nnamespaces = 2;
      final int nruns = 20;
      final boolean reallyLargeLoad = false;

      new ABC(false/* sequential */); // simultaneous start.

      doManyNamespacesConcurrentWritersTest(nnamespaces, nruns, reallyLargeLoad);

   }

}
