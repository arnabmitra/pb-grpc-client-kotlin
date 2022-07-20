package io.provenance.client

import com.google.common.io.BaseEncoding
import com.google.gson.Gson
import com.google.protobuf.Timestamp
import cosmos.auth.v1beta1.QueryOuterClass
import cosmos.bank.v1beta1.Tx
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.tx.v1beta1.ServiceOuterClass
import cosmos.tx.v1beta1.TxOuterClass
import io.provenance.client.grpc.BaseReqSigner
import io.provenance.client.grpc.GasEstimate
import io.provenance.client.grpc.PbClient
import io.provenance.client.protobuf.extensions.toAny
import io.provenance.client.protobuf.extensions.toTxBody
import io.provenance.client.wallet.NetworkType
import io.provenance.client.wallet.WalletSigner
import io.provenance.client.wallet.fromMnemonic
import io.provenance.reward.v1.QualifyingAction
import io.provenance.reward.v1.MsgCreateRewardProgramRequest
import io.provenance.reward.v1.MsgCreateRewardProgramResponse
import io.provenance.reward.v1.RewardProgramByIDRequest
import io.provenance.reward.v1.RewardProgramsRequest
import org.junit.Before
import org.junit.Ignore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tests only work with `make localnet-start` being run on provenance github projects.
// @Ignore
class PbClientTest {

    val pbClient = PbClient(
        chainId = "chain-local",
        channelUri = URI("http://localhost:9090"),
    )
    var mapOfNodeSigners = mutableMapOf<String, WalletSigner>()

    // sample mnemonic, can be anything
    private val mnemonic =
        "tenant radar absurd ostrich music useless broom cup dragon depart annual charge lawsuit aware embark leader hour major venture private near inside daughter cabin" // any mnemonic

    @Before
    fun before() {
        mapOfNodeSigners = getAllVotingKeys()
        val listOfMsgFees = pbClient.getAllMsgFees()?.filter { it.msgTypeUrl == "/cosmos.bank.v1beta1.MsgSend" || it.msgTypeUrl == "/provenance.marker.v1.MsgAddMarkerRequest" }
        if (listOfMsgFees?.size!! != 2) {
            if (listOfMsgFees.filter { it.msgTypeUrl == "/provenance.marker.v1.MsgAddMarkerRequest" }.isEmpty()) {
                createGovProposalAndVote(walletSigners = mapOfNodeSigners, "/provenance.marker.v1.MsgAddMarkerRequest")
            }
            if (listOfMsgFees.filter { it.msgTypeUrl == "/cosmos.bank.v1beta1.MsgSend" }.isEmpty()) {
                createGovProposalAndVote(walletSigners = mapOfNodeSigners, "/cosmos.bank.v1beta1.MsgSend")
            }
        }
    }

    @Test
    fun testClientQuery() {
        pbClient.authClient.accounts(
            QueryOuterClass.QueryAccountsRequest.getDefaultInstance()
        ).also { response ->
            println(response)
            assertTrue(
                response.accountsCount > 0,
                "Found zero accounts on blockchain or could not properly connect to localhost chain."
            )
        }
    }

    /**
     * Example of how to submit a transaction to chain using the new estimate method for Msg fees.
     */
    @Test
    fun testClientTxn() {
        val walletSignerToWallet = fromMnemonic(NetworkType.TESTNET, mnemonic)
        val wallet = mapOfNodeSigners["node0"]!!

        val balanceHashOriginal = pbClient.getAcountBalance(wallet.address(), "nhash")
        val balanceGweiOriginal = pbClient.getAcountBalance(wallet.address(), "gwei")

        // transfer 10000nhash
        val amount = "10000"
        val txn: TxOuterClass.TxBody = Tx.MsgSend.newBuilder()
            .setFromAddress(wallet.address())
            .setToAddress(walletSignerToWallet.address())
            .addAmount(CoinOuterClass.Coin.newBuilder().setDenom("nhash").setAmount(amount))
            .build()
            .toAny()
            .toTxBody() // todo create your own txn

        val baseRequest = pbClient.baseRequest(
            txBody = txn,
            signers = listOf(BaseReqSigner(wallet)),
            1.5f
        )
        val estimate: GasEstimate = pbClient.estimateTx(baseRequest)

        println("estimate is $estimate")
        val estimatedHash = estimate.feesCalculated.firstOrNull { it.denom == "nhash" }
        assertNotNull(estimatedHash, "estimated hash cannot be null")
        val estimatedGwei = estimate.feesCalculated.firstOrNull { it.denom == "gwei" }
        assertNotNull(estimatedGwei, "estimated gwei cannot be null")

        val res = pbClient.estimateAndBroadcastTx(txn, listOf(BaseReqSigner(wallet)), gasAdjustment = 1.5f)
        assertTrue(
            res.txResponse.code == 0,
            "Did not succeed."
        )

        // let the block commit
        Thread.sleep(10000)

        val balanceHash = pbClient.getAcountBalance(wallet.address(), "nhash")
        val balanceGwei = pbClient.getAcountBalance(wallet.address(), "gwei")

        val gweiConsumed = balanceGweiOriginal.amount.toBigDecimal().subtract(balanceGwei.amount.toBigDecimal())
        val hashConsumed = balanceHashOriginal.amount.toBigDecimal().subtract(balanceHash.amount.toBigDecimal()).subtract(amount.toBigDecimal())
        assertEquals(estimatedHash.amount.toString(), hashConsumed.toString(), "estimate should match actual")
        assertEquals(estimatedGwei.amount.toString(), gweiConsumed.toString(), "estimate should match actual")
    }

    @Test
    fun testAddRewardProgram() {
        val wallet = mapOfNodeSigners["node0"]!!

        val txn: TxOuterClass.TxBody = MsgCreateRewardProgramRequest
            .newBuilder()
            .setTitle("title")
            .setDescription("description")
            .setDistributeFromAddress(wallet.address())
            .setTotalRewardPool(CoinOuterClass.Coin.newBuilder().setAmount("1000000000000").setDenom("nhash").build())
            .setMaxRewardPerClaimAddress(CoinOuterClass.Coin.newBuilder().setAmount("1000000").setDenom("nhash").build())
            .setClaimPeriods(10)
            .setProgramStartTime(Timestamp.newBuilder().setSeconds(Timestamp.getDefaultInstance().seconds + 60))
            .setMaxRolloverClaimPeriods(0)
            .setExpireDays(10)
            .addQualifyingActions(QualifyingAction.getDefaultInstance())
            .setClaimPeriodDays(1)
            .build()
            .toAny()
            .toTxBody()
        val res = pbClient.estimateAndBroadcastTx(txn, listOf(BaseReqSigner(wallet)), gasAdjustment = 1.5f, mode = ServiceOuterClass.BroadcastMode.BROADCAST_MODE_BLOCK
        )
        assertTrue(
            res.txResponse.code == 0,
            "Did not succeed."
        )

        val rewardProgramCreatedEvent = res.txResponse.eventsList.find { it.type == "reward_program_created" }
        val programId = rewardProgramCreatedEvent?.attributesList?.find{
            it.key.toString("UTF-8") == "reward_program_id"
        }?.value?.toString("UTF-8")?.toLong()
        assertNotNull (
            programId,
            "could not find reward program id in events"
        )

        val qRes = pbClient.rewardCleint.rewardProgramByID(RewardProgramByIDRequest.newBuilder().setId(programId!!).build())
        assertEquals (
            qRes.rewardProgram.id,
            programId!!,
            "did not find reward program"
        )
    }

    @Test
    fun testClientMultipleTxn() {
        // sample mnemonic
        val walletSignerToWallet = fromMnemonic(NetworkType.TESTNET, mnemonic)
        val wallet = mapOfNodeSigners["node0"]!!

        val balanceHashOriginal = pbClient.getAcountBalance(wallet.address(), "nhash")
        val balanceGweiOriginal = pbClient.getAcountBalance(wallet.address(), "gwei")

        // transfer 10000nhash
        val amount = "10000"
        val txn = Tx.MsgSend.newBuilder()
            .setFromAddress(wallet.address())
            .setToAddress(walletSignerToWallet.address())
            .addAmount(CoinOuterClass.Coin.newBuilder().setDenom("nhash").setAmount(amount))
            .build()
            .toAny()

        val txn2 = Tx.MsgSend.newBuilder()
            .setFromAddress(wallet.address())
            .setToAddress(walletSignerToWallet.address())
            .addAmount(CoinOuterClass.Coin.newBuilder().setDenom("nhash").setAmount(amount))
            .build()
            .toAny()

        val baseRequest = pbClient.baseRequest(
            txBody = listOf(txn, txn2).toTxBody(),
            signers = listOf(BaseReqSigner(wallet)),
            1.5f
        )
        val estimate: GasEstimate = pbClient.estimateTx(baseRequest)

        println("estimate is $estimate")
        val estimatedHash = estimate.feesCalculated.firstOrNull { it.denom == "nhash" }
        assertNotNull(estimatedHash, "estimated hash cannot be null")
        val estimatedGwei = estimate.feesCalculated.firstOrNull { it.denom == "gwei" }
        assertNotNull(estimatedGwei, "estimated gwei cannot be null")

        val res = pbClient.estimateAndBroadcastTx(listOf(txn, txn2).toTxBody(), listOf(BaseReqSigner(wallet)), gasAdjustment = 1.5f)
        assertTrue(
            res.txResponse.code == 0,
            "Did not succeed."
        )

        // let the block commit
        Thread.sleep(10000)

        val balanceHash = pbClient.getAcountBalance(wallet.address(), "nhash")
        val balanceGwei = pbClient.getAcountBalance(wallet.address(), "gwei")

        val gweiConsumed = balanceGweiOriginal.amount.toBigDecimal().subtract(balanceGwei.amount.toBigDecimal())
        val hashConsumed = balanceHashOriginal.amount.toBigDecimal().subtract(balanceHash.amount.toBigDecimal()).subtract(amount.toBigDecimal()).subtract(amount.toBigDecimal())
        assertEquals(estimatedHash.amount.toString(), hashConsumed.toString(), "estimate should match actual")
        assertEquals(estimatedGwei.amount.toString(), gweiConsumed.toString(), "estimate should match actual")
    }

    @Test
    fun testCreateSmartContract() {
        val wallet = mapOfNodeSigners["node0"]!!

        val result = pbClient.storeWasm(wallet)
        assertTrue(
            result.txResponse.code == 0,
            "Did not succeed."
        )
        assertNotNull(result.txResponse.logsList.flatMap { it.eventsList }.filter { it.type == "store_code" }.get(0).attributesList.filter { it.key == "code_id" }.first().value)
    }

    fun getAllVotingKeys(): MutableMap<String, WalletSigner> {
        val mapOfSigners = mutableMapOf<String, WalletSigner>()
        for (i in 0 until 4) {
            val jsonString: String = File("/Users/carltonhanna/code/provenance/build/node$i/key_seed.json").readText(Charsets.UTF_8)
            val map = Gson().fromJson(jsonString, mutableMapOf<String, String>().javaClass)
            val walletSigner = fromMnemonic(NetworkType.COSMOS_TESTNET, map["secret"]!!)
            println(walletSigner.address())
            mapOfSigners.put("node$i", walletSigner)
        }
        return mapOfSigners
    }

    // propose governance and vote
    fun createGovProposalAndVote(walletSigners: Map<String, WalletSigner>, msgType: String) {
        assertTrue { pbClient.addMsgFeeProposal(walletSigners["node0"]!!, msgType).txResponse.code == 0 }

        // let the block be committed
        Thread.sleep(10000)

        // vote on proposal
        val govProp = pbClient.getAllProposalsAndFilter()!!
        assertTrue { pbClient.voteOnProposal(walletSigners["node0"]!!, govProp.proposalId).txResponse.code == 0 }
        assertTrue { pbClient.voteOnProposal(walletSigners["node1"]!!, govProp.proposalId).txResponse.code == 0 }
        assertTrue { pbClient.voteOnProposal(walletSigners["node2"]!!, govProp.proposalId).txResponse.code == 0 }
        assertTrue { pbClient.voteOnProposal(walletSigners["node3"]!!, govProp.proposalId).txResponse.code == 0 }
        Thread.sleep(10000)
    }
}
