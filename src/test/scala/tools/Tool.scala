package tools

import Explorer._
import util.Hash

object Tools {

  val BITCOIN = "docker exec --user bitcoin docker_bitcoin_1 bitcoin-cli -regtest -rpcuser=foo -rpcpassword=bar -rpcport=18333"

  def gen(i:Int): Int = (BITCOIN + " generate " + i.toString) ! ProcessLogger(_ => ())

  def startDocker = "/root/Bitcoin-Graph-Explorer/src/test/docker/start.sh" ! ProcessLogger(_ => ())

  def stopDocker = "/root/Bitcoin-Graph-Explorer/src/test/docker/stop.sh" ! ProcessLogger(_ => ())

  def addTx(n: Long) = ("/root/Bitcoin-Graph-Explorer/src/test/docker/create.sh " + n.toString) ! ProcessLogger(_ => ())

  def count: Int = ((BITCOIN + " getblockcount") !!).trim.toInt

  def saveResume = {
    val init = currentStat
    resume match {
      case Some((a,b,c,d,e,f)) =>
        assertBalancesUpdatedCorrectly(a,b,c,d,e,f)
      case None =>
        assertBalancesCreatedCorrectly
    }

    currentStat.total_closures >= init.total_closures should be (true)
    currentStat.total_addresses >= init.total_addresses should be (true)
  }

  def savePopulate = {
    populate
    assertBalancesCreatedCorrectly
  }

  def s: collection.immutable.Map[Hash, Long] = collection.immutable.Map()

  def x:collection.immutable.Map[Hash, Set[Hash]] = collection.immutable.Map()

  def assertBalancesCreatedCorrectly =
    assertBalancesUpdatedCorrectly(s,s,s,s,s,x)

  def assertBalancesUpdatedCorrectly(
    repsAndAvailable: collection.immutable.Map[Hash, Long],
    adsAndBalances: collection.immutable.Map[Hash, Long],
    repsAndChanges: collection.immutable.Map[Hash, Long],
    changedAddresses: collection.immutable.Map[Hash, Long],
    repsAndBalances: collection.immutable.Map[Hash, Long],
    changedReps: collection.immutable.Map[Hash, Set[Hash]] ): Unit = {
    lazy val ca = changedAddresses - Hash.zero(0)
    lazy val s1 = ca.map(_._2).sum
    lazy val s2 = repsAndChanges.map(_._2).sum
    lazy val b3 = getSumBalance
    lazy val b4 = getSumWalletsBalance
    lazy val message = s"""
  WALLETSs and CHANGES ${str1(repsAndChanges)}
  ADDRESSs and CHANGES ${str1(ca)}
  ADDED REPS ${str1(repsAndBalances)}
  REPS TABLE  ${str2(getClosures())}
  ADDs BALANCES ${str1(getAllBalances())}
  REPS BALANCES ${str1(getAllWalletBalances())}
  WALLETS CHANGED MORE THAN ADDRESSES ###
    ${nr(s2-s1)} btcs
  WALLETs TABLES MORE THAN ADDRESSES ###
    ${nr(b4-b3)} btcs
  UTXO / ADDRESS / WALLET
    ${nr(getUTXOSBalance())} / ${nr(b3)} / ${nr(b4)}
  ${changedReps.map(p=> p._1.toString.drop(2).take(4)+" -> " + p._2.map(_.toString.drop(2).take(4)))}
"""

    for ((rep, balance) <- repsAndBalances)
      if (balance < 0)
        throw new Error(message + "  NEGATIVE VALUE!!!")
      else if (rep != getRepresentant(rep))
        throw new Error(message + "  WTH")

    if (s1 != s2)
      throw new Error(message + "WALLETs CHANGED MORE THAN ADDRESSES!!!")
    else if (b3 != b4)
      throw new Error(message  +"  WALLETs TABLE HAS MORE THAN ADDRESSES!!!")
  }

  // Some help formatter
  def nr(b: Long): String = ((b/10000)/10000.0).toString

  def str1(n: collection.immutable.Map[Hash, Long]): String = if (n.size> 0 && n.size < 25) "#: " + n.size + "  total: " + nr(n.map(_._2).sum) +"\n    " + n.toSeq.sortBy(_._1.toString.drop(2).take(4)).map(m=>m._1.toString.drop(2).take(4)+": "+(nr(m._2))).mkString("\n    ") else n.size.toString

  def str2(n: collection.immutable.Map[Hash, Hash]): String = if (n.size > 0 && n.size < 25) "(" + n.size + ")\n    " + n.toSeq.sortBy(_._1.toString.drop(2).take(4)).map(m=>m._1.toString.drop(2).take(4)+" => "+(m._2.toString.drop(2).take(4))).mkString("\n    ") else n.size.toString

}
