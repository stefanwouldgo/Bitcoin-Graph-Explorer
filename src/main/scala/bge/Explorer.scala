import actions._
import util._
import sys.process._
import collection.mutable.Map

/**
 * Created with IntelliJ IDEA.
 * User: yzark
 * Date: 11/19/1
 * Time: 12:36 PM
 * To change this template use File | Settings | File Templates.
 */
object Explorer extends App with db.BitcoinDB {
  args.toList match{
    case "start"::rest =>

      // Ensure that bitcoind is running
      // Seq("bitcoind","-daemon").run
      // now commented out because bitcoind isn't available on mac by default
      // just start bitcoind -daemon manually

      populate

      Seq("touch",lockFile).!
      iterateResume

    case "populate"::rest             =>

      populate

    case "resume"::rest =>

      iterateResume
      
    case "info"::rest =>

      getInfo

    case _=>

      println("""

        Available commands:

         start: populate, then resume 
         populate: create the database movements with movements and closures.
         resume: update the database generated by populate with new incoming data.
         info
      """)
  }

  def getInfo = {
    val (count, amount) = sumUTXOs
    println("Sum of the utxos saved in the lmdb: "+ amount)
    println("Total utxos in the lmdb: " + count)
    val (countDB, amountDB) = countUTXOs
    println("Sum of the utxos in the sql db " +amountDB)
    println("Total utxos in the sql db " + countDB)
  }

  def totalExpectedSatoshi(blockCount: Int): Long = {
    val epoch = blockCount/210000
    val blocksSinceEpoch = blockCount % 210000
    def blockReward(epoch: Int) = Math.floor(50L*100000000L/Math.pow(2,epoch)).toLong
    val fullEpochs = for (i <- (0 until epoch))
                     yield 210000L * blockReward(i)
    val correct = fullEpochs.sum + blocksSinceEpoch * blockReward(epoch)
    correct - blockReward(0) * (if (blockCount > 91880) 2 else if (blockCount > 191842) 1 else 0)
    // correct for the two duplicate coinbase tx (see BIP 30) that we just store once (they are unspendable anyway)
  }

  def sumUTXOs = {
    lazy val table = LmdbMap.open("utxos")
    lazy val outputMap: UTXOs = new UTXOs (table)
    // (txhash,index) -> (address,value,blockIn)
    val values = for ( (_,(_,value,_)) <- outputMap.view) yield value //makes it a lazy collection
    val tuple = values.grouped(100000).foldLeft((0,0L)){
      case ((count,sum),group) =>
        println(count + " elements read at " + java.util.Calendar.getInstance().getTime())
        val seq = group.toSeq
        (count+seq.size,sum+seq.sum)
    }

    table.close
    tuple
  }

  def populate = {

    val dataDirectory = new java.io.File(dataDir)

    if (!dataDirectory.isDirectory)
      dataDirectory.mkdir

    initializeReaderTables
    initializeClosureTables
    initializeStatsTables

    insertStatistics
 
    PopulateBlockReader
  
    createIndexes
    new PopulateClosure(PopulateBlockReader.processedBlocks)
    createAddressIndexes    
    populateStats
//    testValues

  }

  def resume = {
    val read = new ResumeBlockReader
    val closure = new ResumeClosure(read.processedBlocks)
    println("DEBUG: making new stats")
    resumeStats(read.changedAddresses, closure.changedReps, closure.addedAds, closure.addedReps)

    val lastBlock = chain.getChainHead
    val lastNo = lastBlock.getHeight

    val blockWithHeightBlockCount = (blockCount to lastNo).foldRight(lastBlock){
      case (no,bl) =>
        bl.getPrev(blockStore)
    }

    def rollBackFromBlock(block: org.bitcoinj.core.StoredBlock): Unit =
    {
      val (hash, height) = getLastBlock
      if (block.getHeader.getHash != hash){
        rollBack(height)
        rollBackFromBlock(block.getPrev((blockStore)))
      }
    }

    rollBackFromBlock(blockWithHeightBlockCount)

  }

  def iterateResume = {
    // Seq("bitcoind","-daemon").run
    
    if (!peerGroup.isRunning) startBitcoinJ

    // if there are more stats than blocks we could delete it
    for (block <- getWrongBlock){
      
      rollBack(block)
      populateStats 
      assert(getWrongBlock == None, "The database is inconsistent")
    }

    while (new java.io.File(lockFile).exists)
    {
      if (blockCount > chain.getBestChainHeight)
      {
        println("waiting for new blocks at " + java.util.Calendar.getInstance().getTime())
        chain.getHeightFuture(blockCount).get //wait until the chain is at least 6 blocks longer than we have read
      }

      //testValues
      resume
               

     // }
     // else
     // {
     //   println("waiting for new blocks")
     //   waitIfNewBlocks(to)
     // }
    }
    println("process stopped")
    //Seq("bitcoin-cli","stop").run
  }

  def getWrongBlock: Option[Int] = {
    
    val (count,amount) = sumUTXOs
    val (countDB, amountDB) = countUTXOs
    val bc = blockCount
    val expected = totalExpectedSatoshi(bc)
    val utxosMaxHeight = getUtxosMaxHeight 
    
    val lch = lastCompletedHeight
    val sameCount = count == countDB
    val sameValue = amount == amountDB
    val rightValue = amount <= expected
    val rightBlock = (blockCount - 1 == lch) && utxosMaxHeight == lch

    if (!rightValue)  println("we have " + ((amount-expected)/100000000.0) + " too many bitcoins")
    if (!sameCount)   println("we lost utxos")
    if (!sameValue)   println("different sum of btcs in db and lmdb")
    if (!rightBlock)  println("wrong or incomplete block")

    if (sameCount && sameValue && rightValue && rightBlock) None
    else if (utxosMaxHeight > bc -1) Some(utxosMaxHeight)
    else if (bc - 1 > lch) Some(bc-1)
    else throw new Exception("This should not have happened. Maybe we found a new bug in Scala.")
  }

  def resumeStats(changedAddresses: Map[Hash,Long], changedReps: Map[Hash,Set[Hash]], addedAds: Int, addedReps: Int)  = {
    
    println("DEBUG: "+ changedAddresses.size + " addresses changed balance")

    if (changedAddresses.size < 38749 )
    {
      updateBalanceTables(changedAddresses, changedReps)
      insertRichestAddresses
      insertRichestClosures
      updateStatistics(changedReps,addedAds, addedReps)
    }
    else populateStats
        
  }

  def populateStats = {
    createBalanceTables
    insertRichestAddresses
    insertRichestClosures
    insertStatistics
  }


}
