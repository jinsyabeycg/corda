package com.r3corda.core.transactions

import com.r3corda.core.contracts.Command
import com.r3corda.core.contracts.ContractState
import com.r3corda.core.contracts.StateRef
import com.r3corda.core.contracts.TransactionState
import com.r3corda.core.crypto.MerkleTreeException
import com.r3corda.core.crypto.PartialMerkleTree
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.crypto.sha256
import com.r3corda.core.serialization.serialize
import java.util.*

/**
 * Creation and verification of a Merkle Tree for a Wire Transaction.
 *
 * Tree should be the same no matter the ordering of outputs, inputs, attachments and commands.
 * Transaction is split into following blocks: inputs, outputs, commands, attachments' refs.
 * If a row in a tree has an odd number of elements - the final hash is hashed with itself.
 */

//Todo It's just mess, move it to wtx
fun WireTransaction.buildFilteredTransaction(filterFuns: FilterFuns): MerkleTransaction{
    return MerkleTransaction.buildMerkleTransaction(this, filterFuns)
}

fun WireTransaction.calculateLeavesHashes(): List<SecureHash>{
    val resultHashes = ArrayList<SecureHash>()
    val entries = listOf(inputs, outputs, attachments, commands)
    entries.forEach { it.sortedBy { x-> x.hashCode() }.mapTo(resultHashes, { xy -> serializedHash(x) }) }
    return resultHashes
}

fun SecureHash.hashConcat(other: SecureHash) = (this.bits + other.bits).sha256()
fun <T: Any> serializedHash(x: T) = x.serialize().hash

/**
 * Builds the tree from bottom to top. Takes as an argument list of leaves hashes.
 */
tailrec fun getMerkleRoot(
        lastHashList: List<SecureHash>): SecureHash{
    if(lastHashList.size < 1)
        throw MerkleTreeException("Cannot calculate Merkle root on empty hash list.")
    if(lastHashList.size == 1) {
        return lastHashList[0]
    }
    else{
        val newLevelHashes: MutableList<SecureHash> = ArrayList()
        var i = 0
        while(i < lastHashList.size){
            val left = lastHashList[i]
            //If there is an odd number of elements, the last element is hashed with itself.
            val right = lastHashList[Math.min(i+1, lastHashList.size - 1)]
            val combined = left.hashConcat(right)
            newLevelHashes.add(combined)
            i+=2
        }
        return getMerkleRoot(newLevelHashes)
    }
}

/**
 * Class that holds filtered leaves for a partial Merkle transaction. We assume mixed leaves types.
 */
class FilteredLeaves(
        val inputs: List<StateRef>,
        val outputs: List<TransactionState<ContractState>>,
        val attachments: List<SecureHash>,
        val commands: List<Command>
){
    fun getFilteredHashes(): List<SecureHash>{
        val resultHashes = ArrayList<SecureHash>()
        val entries = listOf(inputs, outputs, attachments, commands)
        entries.forEach { it.mapTo(resultHashes, { x -> serializedHash(x) }) }
        return resultHashes
    }
}

open class FilterFuns(val filterInputs: (StateRef) -> Boolean = { false },
                      val filterOutputs: (TransactionState<ContractState>) -> Boolean = { false },
                      val filterAttachments: (SecureHash) -> Boolean = { false },
                      val filterCommands: (Command) -> Boolean = { false }){
    fun <T: Any> genericFilter(elem: T): Boolean{
        return when (elem) {
            is StateRef -> filterInputs(elem)
            is TransactionState<*> -> filterOutputs(elem)
            is SecureHash -> filterAttachments(elem)
            is Command -> filterCommands(elem)
            else -> throw IllegalArgumentException("Wrong argument type: ${elem.javaClass}")
        }
    }
}

/**
 * Class representing merkleized filtered transaction.
 * filteredLeaves - are the leaves included in a filtered transaction.
 * partialMerkleTree - Merkle branch needed to verify that filtered transaction.
 */
class MerkleTransaction(
        val filteredLeaves: FilteredLeaves,
        val partialMerkleTree: PartialMerkleTree
){
    companion object {
        /**
         * Construction of filtered transaction with Partial Merkle Tree, takes WireTransaction and filtering functions
         * for inputs, outputs, attachments, commands.
         */
        fun buildMerkleTransaction(wtx: WireTransaction,
                                   filterFuns: FilterFuns
        ): MerkleTransaction {
            val includeLeaves: MutableList<Boolean> = ArrayList()

            val filteredInputs: MutableList<StateRef> = ArrayList()
            val filteredOutputs: MutableList<TransactionState<ContractState>> = ArrayList()
            val filteredAttachments: MutableList<SecureHash> = ArrayList()
            val filteredCommands: MutableList<Command> = ArrayList()

            //It's a little evil, I needed a way of building at once few lists.
            fun <T: Any> filterLeaves(el: T, destination: MutableList<T>){
                val include = filterFuns.genericFilter(el)
                if (include) destination.add(el)
                includeLeaves.add(include)
            }

            //TODO Ordering by hashCode
            wtx.inputs.sortedBy { it.hashCode() }.forEach { filterLeaves(it, filteredInputs) }
            wtx.outputs.sortedBy { it.hashCode() }.forEach { filterLeaves(it, filteredOutputs) }
            wtx.attachments.sortedBy { it.hashCode() }.forEach { filterLeaves(it, filteredAttachments) }
            wtx.commands.sortedBy { it.hashCode() }.forEach { filterLeaves(it, filteredCommands) }

            val filteredLeaves = FilteredLeaves(filteredInputs, filteredOutputs, filteredAttachments, filteredCommands)

            val pmt = PartialMerkleTree.build(includeLeaves, wtx.allLeavesHashes)
            return MerkleTransaction(filteredLeaves, pmt)
        }
    }

    //todo exception
    /**
     * Runs verification of Partial Merkle Branch with provided merkleRoot.
     */
    fun verify(merkleRoot: SecureHash):Boolean{
        val hashes: List<SecureHash> = filteredLeaves.getFilteredHashes()
        if(hashes.size == 0)
            throw MerkleTreeException("Transaction without included leaves.")
        return partialMerkleTree.verify(hashes, merkleRoot)
    }
}