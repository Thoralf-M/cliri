package com.iota.iri.service.tipselection.impl;

import com.iota.iri.LedgerValidator;
import com.iota.iri.TransactionTestUtils;
import com.iota.iri.TransactionValidator;
import com.iota.iri.conf.MainnetConfig;
import com.iota.iri.conf.TipSelConfig;
import com.iota.iri.controllers.TransactionViewModel;
import com.iota.iri.controllers.TransactionViewModelTest;
import com.iota.iri.model.Hash;
import com.iota.iri.storage.Tangle;
import com.iota.iri.storage.rocksDB.RocksDBPersistenceProvider;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.HashMap;
import java.util.HashSet;

@RunWith(MockitoJUnitRunner.class)
public class WalkValidatorImplTest {

    private static final TemporaryFolder dbFolder = new TemporaryFolder();
    private static final TemporaryFolder logFolder = new TemporaryFolder();
    private static Tangle tangle;
    private TipSelConfig config = new MainnetConfig();
    @Mock
    private LedgerValidator ledgerValidator;
    @Mock
    private TransactionValidator transactionValidator;
    
    @AfterClass
    public static void tearDown() throws Exception {
        tangle.shutdown();
        dbFolder.delete();
    }

    @BeforeClass
    public static void setUp() throws Exception {
        tangle = new Tangle();
        dbFolder.create();
        logFolder.create();
        tangle.addPersistenceProvider(new RocksDBPersistenceProvider(dbFolder.getRoot().getAbsolutePath(), logFolder
                .getRoot().getAbsolutePath(), 1000));
        tangle.init();
    }

    @Test
    public void shouldPassValidation() throws Exception {
        int depth = 15;
        TransactionViewModel tx = TransactionTestUtils.createBundleHead(0);
        tx.updateSolid(true);
        tx.store(tangle);
        Hash hash = tx.getHash();
        Mockito.when(ledgerValidator.updateDiff(new HashSet<>(), new HashMap<>(), hash))
                .thenReturn(true);

        WalkValidatorImpl walkValidator = new WalkValidatorImpl(tangle, ledgerValidator, config);
        Assert.assertTrue("Validation failed", walkValidator.isValid(hash));
    }

    @Test
    public void failOnTxType() throws Exception {
        int depth = 15;
        TransactionViewModel tx = TransactionTestUtils.createBundleHead(0);
        tx.store(tangle);
        Hash hash = tx.getTrunkTransactionHash();
        tx.updateSolid(true);
        Mockito.when(ledgerValidator.updateDiff(new HashSet<>(), new HashMap<>(), hash))
                .thenReturn(true);

        WalkValidatorImpl walkValidator = new WalkValidatorImpl(tangle, ledgerValidator, config);
        Assert.assertFalse("Validation succeded but should have failed since tx is missing", walkValidator.isValid(hash));
    }

    @Test
    public void failOnTxIndex() throws Exception {
        TransactionViewModel tx = TransactionTestUtils.createBundleHead(2);
        tx.store(tangle);
        Hash hash = tx.getHash();
        tx.updateSolid(true);
        Mockito.when(ledgerValidator.updateDiff(new HashSet<>(), new HashMap<>(), hash))
                .thenReturn(true);

        WalkValidatorImpl walkValidator = new WalkValidatorImpl(tangle, ledgerValidator, config);
        Assert.assertFalse("Validation succeded but should have failed since we are not on a tail", walkValidator.isValid(hash));
    }

    @Test
    public void failOnSolid() throws Exception {
        TransactionViewModel tx = TransactionTestUtils.createBundleHead(0);
        tx.store(tangle);
        Hash hash = tx.getHash();
        tx.updateSolid(false);
        Mockito.when(ledgerValidator.updateDiff(new HashSet<>(), new HashMap<>(), hash))
                .thenReturn(true);
        
        WalkValidatorImpl walkValidator = new WalkValidatorImpl(tangle, ledgerValidator, config);
        Assert.assertFalse("Validation succeded but should have failed since tx is not solid",
                walkValidator.isValid(hash));
    }

    @Test
    public void failOnBelowMaxDepthDueToOldMilestone() throws Exception {
        TransactionViewModel tx = TransactionTestUtils.createBundleHead(0);
        tx.store(tangle);
        tx.setSnapshot(tangle, 2);
        Hash hash = tx.getHash();
        tx.updateSolid(true);
        Mockito.when(ledgerValidator.updateDiff(new HashSet<>(), new HashMap<>(), hash))
                .thenReturn(true);
        WalkValidatorImpl walkValidator = new WalkValidatorImpl(tangle, ledgerValidator, config);
        Assert.assertFalse("Validation succeeded but should have failed tx is below max depth",
                walkValidator.isValid(hash));
    }

    @Test
    public void belowMaxDepthWithFreshMilestone() throws Exception {
        TransactionViewModel tx = TransactionTestUtils.createBundleHead(0);
        tx.store(tangle);
        tx.setSnapshot(tangle, 92);
        Hash hash = tx.getHash();
        for (int i = 0; i < 4 ; i++) {
            tx = new TransactionViewModel(TransactionViewModelTest.getRandomTransactionWithTrunkAndBranch(hash, hash), TransactionViewModelTest.getRandomTransactionHash());
            TransactionTestUtils.setLastIndex(tx,0);
            TransactionTestUtils.setCurrentIndex(tx,0);
            tx.updateSolid(true);
            hash = tx.getHash();
            tx.store(tangle);
        }
        Mockito.when(ledgerValidator.updateDiff(new HashSet<>(), new HashMap<>(), hash))
                .thenReturn(true);
        WalkValidatorImpl walkValidator = new WalkValidatorImpl(tangle, ledgerValidator, config);
        Assert.assertTrue("Validation failed but should have succeeded since tx is above max depth",
                walkValidator.isValid(hash));
    }


    @Test
    public void belowMaxDepthOnGenesis() throws Exception {
        TransactionViewModel tx = null;
        final int maxAnalyzedTxs = config.getBelowMaxDepthTransactionLimit();
        Hash hash = Hash.NULL_HASH;
        for (int i = 0; i < maxAnalyzedTxs - 2 ; i++) {
            tx = new TransactionViewModel(TransactionViewModelTest.getRandomTransactionWithTrunkAndBranch(hash, hash), TransactionViewModelTest.getRandomTransactionHash());
            TransactionTestUtils.setLastIndex(tx,0);
            TransactionTestUtils.setCurrentIndex(tx,0);
            tx.updateSolid(true);
            hash = tx.getHash();
            tx.store(tangle);
        }
        Mockito.when(ledgerValidator.updateDiff(new HashSet<>(), new HashMap<>(), tx.getHash()))
                .thenReturn(true);
        WalkValidatorImpl walkValidator = new WalkValidatorImpl(tangle, ledgerValidator, config);
        Assert.assertTrue("Validation failed but should have succeeded. We didn't exceed the maximal amount of" +
                        "transactions that may be analyzed.",
                walkValidator.isValid(tx.getHash()));
    }

    @Test
    public void failOnInconsistency() throws Exception {
        TransactionViewModel tx = TransactionTestUtils.createBundleHead(0);
        tx.store(tangle);
        Hash hash = tx.getHash();
        tx.updateSolid(true);
        Mockito.when(ledgerValidator.updateDiff(new HashSet<>(), new HashMap<>(), hash))
                .thenReturn(false);
        WalkValidatorImpl walkValidator = new WalkValidatorImpl(tangle, ledgerValidator, config);
        Assert.assertFalse("Validation succeded but should have failed due to inconsistent ledger state",
                walkValidator.isValid(hash));
    }

}