package core

// for blocks db and longestChain
import org.bitcoinj.core.Utils._
import org.bitcoinj.core._

import util._

import scala.collection.JavaConverters._
import scala.slick.driver.SQLiteDriver.simple._
import scala.slick.jdbc.JdbcBackend.Database.dynamicSession

// extends libs.BlockSource means that it depends on a libs.BlockSource
trait BlockReader extends BlockSource {

  def saveTransaction(transaction: Transaction)

  def saveBlock(b: Hash): Unit

  def pre: Unit

  def post: Unit

  var savedBlockSet: Set[Hash] = Set.empty
  val longestChain = getLongestBlockChainHashSet

  transactionDBSession {
    pre

    val savedBlocks = for (b <- blockDB)
    yield (b.hash)

    for (c <- savedBlocks)
      savedBlockSet = savedBlockSet + Hash(c)

    var a = 1
    var time = System.currentTimeMillis

    for (transaction <- transactionSource) {
      saveTransaction(transaction)

      if (a % 10000 == 0) {
        val t = System.currentTimeMillis - time
        System.out.println("Processed " + a + " transactions in " + t + " using " + 1000 * t / a + " µs/tx");
      }

      a += 1
    }

    post
  }

  def blockFilter(b: Block) = {
    val blockHash = Hash(b.getHash.getBytes)
    (longestChain contains blockHash) && !(savedBlockSet contains blockHash)
  }

  def withoutDuplicates(b: Block, t: Transaction): Boolean =
    !(Hash(b.getHash.getBytes) == Hash("00000000000a4d0a398161ffc163c503763b1f4360639393e0e4c8e300e0caec") &&
      Hash(t.getHash.getBytes) == Hash("d5d27987d2a3dfc724e359870c6644b40e497bdc0589a033220fe15429d88599")) &&
      !(Hash(b.getHash.getBytes) == Hash("00000000000a4d0a3B83B59A507C6B843DE3DB4E365B141621FB2381A2641B16C4E10C110E1C2EFBD98161ffc163c503763b1f4360639393e0e4c8e300e0caec") &&
        Hash(t.getHash.getBytes) == Hash("d5d27987d2a3dfc724e359870c6644b40e497bdc0589a033220fe15429d88599"))

  // TODO: use take and slice to avoid reading the whole block file
  lazy val filteredBlockSource =
    blockSource withFilter blockFilter

  def transactionsInBlock(b: Block) =
    b.getTransactions.asScala filter (t => withoutDuplicates(b, t))

  def inputsInTransaction(t: Transaction) =
    if (!t.isCoinBase) t.getInputs.asScala
    else List.empty

  def outputsInTransaction(t: Transaction) =
    t.getOutputs.asScala

  lazy val transactionSource: Iterator[Transaction] = filteredBlockSource flatMap { b => saveBlock(Hash(b.getHash.getBytes)); transactionsInBlock(b)}

  def getAddressFromOutput(output: TransactionOutput): Option[Array[Byte]] =
    bitcoinjParseScript(output).
    orElse(customParseScript(output)).
    orElse(noAddressParsePossible(output))

  def bitcoinjParseScript(output: TransactionOutput) =
    getVersionedHashFromAddress(
      tryToGetAddress(output).
        orElse(tryGetAddressFromP2PKHScript(output)).
        orElse(tryGetAddressFromP2SH(output)))

  def tryToGetAddress(output: TransactionOutput) = {
    try {
      Option(output.getScriptPubKey.getToAddress(params))
    }
    catch{
      case e: Exception =>
        None
    }
  }

  def tryGetAddressFromP2SH(output: TransactionOutput) =
    try {
      Option(output.getAddressFromP2SH(params))
    }
    catch {
      case e: Exception =>
        None
    }

  def tryGetAddressFromP2PKHScript(output: TransactionOutput) =
    try {
      Option(output.getAddressFromP2PKHScript(params))
    }
    catch {
      case e: Exception =>
        None
    }

  def noAddressParsePossible(output: TransactionOutput) = {
    try {
      println("ERROR:"+output.getParentTransaction.getHash+":"+output.getScriptPubKey.toString)
      None
    }
    catch {
      case e: Exception =>
        println("ERROR:"+output.getParentTransaction.getHash+":"+e.getMessage)
        None
    }

  }

  def customParseScript(output: TransactionOutput): Option[Array[Byte]] =
    parseChecksigScript(output).
    orElse(parseMultisigScript(output))

  def parseChecksigScript(output: TransactionOutput): Option[Array[Byte]] = {
    try{
      val script: String = output.getScriptPubKey.toString

      if (!script.contains("CHECKSIG"))
      {
        return None
      }

      // Hash expression "[hexadecimal]" with length of 33 or 65
      // Search for a 33 or 65 pubkey and decode it
      val rawPubkeys = findHashListFromScript(script)

      if (rawPubkeys.nonEmpty)
      {
        val hexa= rawPubkeys.head.slice(1, rawPubkeys.head.length - 1)
        val pubkey = Hash(hexa).array.toArray
        val address = new Address(params, sha256hash160(pubkey))
        getVersionedHashFromAddress(Some(address))
      }
      else
        None
    }
    catch{
      case e: Exception =>
        None
    }
  }

  def parseMultisigScript(output: TransactionOutput): Option[Array[Byte]] = {
    try {
      val script: String = output.getScriptPubKey.toString
      // Ignore if there is not a CHECKMULTISIG
      if (script.contains("CHECKMULTISIGVERIF") || !script.contains("CHECKMULTISIG"))
        return None

      val rawPubkeys = findHashListFromScript(script)

      val pubkeys = for (pubkey <- rawPubkeys)
        yield getHashFromPubkeyAsScriptString(pubkey)

      val rawNumbers = findNumericListFromScript(script)
      // TODO: if there is not first number, is it right to get the length?
      val firstNumber =
        if (!rawNumbers.isEmpty)
          rawNumbers.head.toInt
        else
          pubkeys.length

      if (pubkeys.isEmpty)
        None // no pubkeys => script must be incomplete!
      else
        Some(Array(firstNumber.toByte) ++ pubkeys.reduce { (a, b) => a ++ b})
    }
    catch{
      case e: Exception =>
        None
    }
  }

  def findHashListFromScript(script: String): List[String] =
    """\[([^\]]{66}|[^\]]{130})\]""".r.findAllIn(script).toList

  def findNumericListFromScript(script: String): List[String] =
    """[ |^](0-9)*[ |$]""".r.findAllIn(script).toList


  def getHashFromPubkeyAsScriptString(pubkey: String): Array[Byte] =
    sha256hash160(Hash(pubkey.slice(1, pubkey.length - 1)).array.toArray)

  def getVersionedHashFromAddress(address: Option[Address]): Option[Array[Byte]] =
    address match {
      case None => None
      case Some(address) => Some((Array(address.getVersion.toByte) ++ address.getHash160).toArray)
    }

}
