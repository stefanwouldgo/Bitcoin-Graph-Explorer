package util

/**
 * Created by IntelliJ IDEA.
 * User: stefan
 * Date: 4/12/12
 * Time: 4:30 PM
 * To change this template use File | Settings | File Templates.
 */

case class DisjointSetOfAddresses(val address: Hash) {

  var rank = 0
  var parent: DisjointSetOfAddresses = this // takes less RAM than an Option
  //var children: Set[DisjointSetOfAddresses] = Set.empty  // to be able to return all members of a set


//  def equals(that:DisjointSetOfAddresses) = // not necessary because it is a case class
//    this.address == that.address

  def union(that: DisjointSetOfAddresses): DisjointSetOfAddresses = {
    val left = this.find
    val right = that.find
    if (left == right) return left

    if (left.rank == right.rank)   // weighted union
      right.rank += 1

    if (left.rank <= right.rank) {
      left.parent = right
   //   right.children += left
      right
    }
    else {
      right.parent = left
   //   left.children += right
      left
    }


  }

  def find: DisjointSetOfAddresses = {
    val p = this.parent
    if (p == this) this
    else 
      { val r = p.find
        this.parent = r
    //    r.children += this          // path compression
        r 
      }
        
  }

}
