package io.provenance.client

import com.google.gson.Gson
import com.google.protobuf.Timestamp
import cosmos.auth.v1beta1.QueryOuterClass
import cosmos.bank.v1beta1.Tx
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.tx.v1beta1.ServiceOuterClass
import cosmos.tx.v1beta1.TxOuterClass
import io.provenance.client.grpc.BaseReqSigner
import io.provenance.client.grpc.PbClient
import io.provenance.client.protobuf.extensions.toAny
import io.provenance.client.protobuf.extensions.toTxBody
import io.provenance.client.wallet.NetworkType
import io.provenance.client.wallet.WalletSigner
import io.provenance.client.wallet.fromMnemonic
import io.provenance.reward.v1.*
import io.provenance.reward.v1.RewardAccountState.ClaimStatus
import org.junit.Before
import java.io.File
import java.net.URI
import java.time.Instant
import kotlin.test.*

// Tests only work with `make localnet-start` being run on provenance github projects.
// @Ignore
class PbClientRewardTest {

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
        mapOfNodeSigners = getAllNodeKeys()
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

    @Test
    fun testAddRewardProgramWithTransferDelegation() {
        val wallet = mapOfNodeSigners["node0"]!!
        val walletSignerToWallet = fromMnemonic(NetworkType.TESTNET, mnemonic)
        val futureSeconds = 20L
        val instant = Instant.now()
        val now = Timestamp.newBuilder().setSeconds(instant.getEpochSecond() + futureSeconds)
            .setNanos(instant.getNano()).build();

        val transferQA = ActionTransfer
            .newBuilder()
            .setMaximumActions(10)
            .setMinimumActions(1)
            .setMinimumDelegationAmount(CoinOuterClass.Coin.newBuilder().setAmount("0").setDenom("nhash").build())
            .build()
        val txn: TxOuterClass.TxBody = MsgCreateRewardProgramRequest
            .newBuilder()
            .setTitle("title")
            .setDescription("description")
            .setDistributeFromAddress(wallet.address())
            .setTotalRewardPool(CoinOuterClass.Coin.newBuilder().setAmount("1000000000000").setDenom("nhash").build())
            .setMaxRewardPerClaimAddress(CoinOuterClass.Coin.newBuilder().setAmount("1000000").setDenom("nhash").build())
            .setClaimPeriods(1)
            .setProgramStartTime(now)
            .setMaxRolloverClaimPeriods(0)
            .setExpireDays(10)
            .addQualifyingActions(QualifyingAction.newBuilder().setTransfer(transferQA).build())
            .setClaimPeriodDays(1)
            .build()
            .toAny()
            .toTxBody()
        var res = pbClient.estimateAndBroadcastTx(txn, listOf(BaseReqSigner(wallet)), gasAdjustment = 1.5f, mode = ServiceOuterClass.BroadcastMode.BROADCAST_MODE_BLOCK
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

        // test finding new program by pending
        val rewardProgramsResponse = pbClient.rewardCleint.rewardPrograms(QueryRewardProgramsRequest
            .newBuilder()
            .setQueryType(QueryRewardProgramsRequest.QueryType.PENDING)
            .build()
        )

        assertTrue (
            !rewardProgramsResponse.rewardProgramsList.filter { rp -> rp.id == programId }.isEmpty(),
            "did not find reward program"
        )

        Thread.sleep(futureSeconds * 1000)

        // test find program by id...should have changed to started after sleep
        var rewardProgramByIdResponse = getRewardProgramById(programId)
        assertEquals (
            programId,
            rewardProgramByIdResponse.rewardProgram.id,
            "did not find reward program"
        )
        if (RewardProgram.State.STARTED == rewardProgramByIdResponse.rewardProgram.state) {
            println("${rewardProgramByIdResponse.rewardProgram.id} has started.")
        }
        assertEquals(
            RewardProgram.State.STARTED,
            rewardProgramByIdResponse.rewardProgram.state,

            )

        // send two transactions to activate the qualifying action
        val send1 = Tx.MsgSend.newBuilder()
            .setFromAddress(wallet.address())
            .setToAddress(walletSignerToWallet.address())
            .addAmount(CoinOuterClass.Coin.newBuilder().setDenom("nhash").setAmount("1"))
            .build()
            .toAny()

        val send2 = Tx.MsgSend.newBuilder()
            .setFromAddress(wallet.address())
            .setToAddress(walletSignerToWallet.address())
            .addAmount(CoinOuterClass.Coin.newBuilder().setDenom("nhash").setAmount("1"))
            .build()
            .toAny()
        res = pbClient.estimateAndBroadcastTx(listOf(send1, send2).toTxBody(), listOf(BaseReqSigner(wallet)), gasAdjustment = 2f, mode = ServiceOuterClass.BroadcastMode.BROADCAST_MODE_BLOCK)
        assertTrue(
            res.txResponse.code == 0,
            "Did not succeed."
        )

        // test getting the distribution for program and claim period
        val claimPeriodRewardDistributionByIDResponse = pbClient.rewardCleint.claimPeriodRewardDistributionsByID(QueryClaimPeriodRewardDistributionByIDRequest
            .newBuilder()
            .setRewardId(programId)
            .setClaimPeriodId(1L)
            .build())
        println(claimPeriodRewardDistributionByIDResponse)
        assertEquals(
            claimPeriodRewardDistributionByIDResponse.claimPeriodRewardDistribution.totalShares,
            2,
            "Account should have 2 shares for reward program."
        )

        println("Add Break Point here and change computers date...")
        Thread.sleep(20000) // sleep to assure it ends program

        rewardProgramByIdResponse = getRewardProgramById(programId)
        println("reward program after time change ${rewardProgramByIdResponse}")
        var rewardResponse = pbClient.rewardCleint.queryRewardDistributionsByAddress(QueryRewardsByAddressRequest.newBuilder()
            .setAddress(wallet.address())
            .build())
        var accountState = rewardResponse.rewardAccountStateList.filter { ras -> ras.rewardProgramId == programId }.first()
        println("Before Claiming ${accountState}")
        assertEquals(
            ClaimStatus.CLAIMABLE,
            accountState.claimStatus,
            "status should be claimable"
        )

        // test claim reward tx
        val claimRewardTx = MsgClaimRewardRequest.newBuilder().setRewardProgramId(programId).setRewardAddress(wallet.address()).build().toAny().toTxBody()
        res = pbClient.estimateAndBroadcastTx(claimRewardTx, listOf(BaseReqSigner(wallet)), gasAdjustment = 1.5f, mode = ServiceOuterClass.BroadcastMode.BROADCAST_MODE_BLOCK
        )
        assertTrue(
            res.txResponse.code == 0,
            "Did not succeed."
        )

        // determine if the reward has been claimed
        rewardResponse = pbClient.rewardCleint.queryRewardDistributionsByAddress(QueryRewardsByAddressRequest.newBuilder()
            .setAddress(wallet.address())
            .build())
        accountState = rewardResponse.rewardAccountStateList.filter { ras -> ras.rewardProgramId == programId }.first()
        println("Before Claiming ${accountState}")
        assertEquals(
            ClaimStatus.CLAIMED,
            accountState.claimStatus,
            "status should be claimed"
        )
    }

    fun getRewardProgramById(id : Long) : QueryRewardProgramByIDResponse{
        return pbClient.rewardCleint.rewardProgramByID(QueryRewardProgramByIDRequest.newBuilder()
            .setId(id)
            .build())
    }

    fun getAllNodeKeys(): MutableMap<String, WalletSigner> {
        val mapOfSigners = mutableMapOf<String, WalletSigner>()
        for (i in 0 until 4) {
            val jsonString: String = File("../provenance/build/node$i/key_seed.json").readText(Charsets.UTF_8)
            val map = Gson().fromJson(jsonString, mutableMapOf<String, String>().javaClass)
            val walletSigner = fromMnemonic(NetworkType.COSMOS_TESTNET, map["secret"]!!)
            println(walletSigner.address())
            mapOfSigners.put("node$i", walletSigner)
        }
        return mapOfSigners
    }

    // TODO figure out how to get run net keys
    fun getRunKeys(): MutableMap<String, WalletSigner> {
        val mapOfSigners = mutableMapOf<String, WalletSigner>()
        val jsonString: String = File("../provenance/build/run/provenanced/config/node_key.json").readText(Charsets.UTF_8)
        val map = Gson().fromJson(jsonString, mutableMapOf<String, String>().javaClass)
        val walletSigner = fromMnemonic(NetworkType.COSMOS_TESTNET, map["secret"]!!)
        println(walletSigner.address())
        mapOfSigners.put("node1", walletSigner)
        return mapOfSigners
    }
}
