package net.corda.finance.contracts

import net.corda.core.contracts.*
import net.corda.core.crypto.NullKeys.NULL_PARTY
import net.corda.core.crypto.toStringShort
import net.corda.core.identity.AbstractParty
import net.corda.core.internal.Emoji
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.schemas.CommercialPaperSchemaV1
import net.corda.finance.contracts.utils.sumCashBy
import java.time.Instant
import java.util.*

const val CP_PROGRAM_ID = "net.corda.finance.contracts.CommercialPaper"

// TODO: Generalise the notion of an owned instrument into a superclass/supercontract. Consider composition vs inheritance.
class CommercialPaper : Contract {
    companion object {
        const val CP_PROGRAM_ID: ContractClassName = "net.corda.finance.contracts.CommercialPaper"
    }

    data class State(
            val issuance: PartyAndReference,
            override val owner: AbstractParty,
            val faceValue: Amount<Issued<Currency>>,
            val maturityDate: Instant
    ) : OwnableState, QueryableState, ICommercialPaperState {
        override val participants = listOf(owner)

        override fun withNewOwner(newOwner: AbstractParty) = CommandAndState(Commands.Move(), copy(owner = newOwner))
        fun withoutOwner() = copy(owner = NULL_PARTY)
        override fun toString() = "${Emoji.newspaper}CommercialPaper(of $faceValue redeemable on $maturityDate by '$issuance', owned by $owner)"

        // Although kotlin is smart enough not to need these, as we are using the ICommercialPaperState, we need to declare them explicitly for use later,
        override fun withOwner(newOwner: AbstractParty): ICommercialPaperState = copy(owner = newOwner)

        override fun withFaceValue(newFaceValue: Amount<Issued<Currency>>): ICommercialPaperState = copy(faceValue = newFaceValue)
        override fun withMaturityDate(newMaturityDate: Instant): ICommercialPaperState = copy(maturityDate = newMaturityDate)

        /** Object Relational Mapping support. */
        override fun supportedSchemas(): Iterable<MappedSchema> = listOf(CommercialPaperSchemaV1)
        /** Additional used schemas would be added here (eg. CommercialPaperV2, ...) */

        /** Object Relational Mapping support. */
        override fun generateMappedObject(schema: MappedSchema): PersistentState {
            return when (schema) {
                is CommercialPaperSchemaV1 -> CommercialPaperSchemaV1.PersistentCommercialPaperState(
                        issuancePartyHash = this.issuance.party.owningKey.toStringShort(),
                        issuanceRef = this.issuance.reference.bytes,
                        ownerHash = this.owner.owningKey.toStringShort(),
                        maturity = this.maturityDate,
                        faceValue = this.faceValue.quantity,
                        currency = this.faceValue.token.product.currencyCode,
                        faceValueIssuerPartyHash = this.faceValue.token.issuer.party.owningKey.toStringShort(),
                        faceValueIssuerRef = this.faceValue.token.issuer.reference.bytes
                )
            /** Additional schema mappings would be added here (eg. CommercialPaperV2, ...) */
                else -> throw IllegalArgumentException("Unrecognised schema $schema")
            }
        }

        /** @suppress */
        infix fun `owned by`(owner: AbstractParty) = copy(owner = owner)
    }

    interface Commands : CommandData {
        class Move : TypeOnlyCommandData(), Commands

        class Redeem : TypeOnlyCommandData(), Commands
        // We don't need a nonce in the issue command, because the issuance.reference field should already be unique per CP.
        // However, nothing in the platform enforces that uniqueness: it's up to the issuer.
        class Issue : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction) {
        // Group by everything except owner: any modification to the CP at all is considered changing it fundamentally.
        val groups = tx.groupStates(State::withoutOwner)

        // There are two possible things that can be done with this CP. The first is trading it. The second is redeeming
        // it for cash on or after the maturity date.
        val command = tx.commands.requireSingleCommand<CommercialPaper.Commands>()
        val timeWindow: TimeWindow? = tx.timeWindow

        // Suppress compiler warning as 'key' is an unused variable when destructuring 'groups'.
        @Suppress("UNUSED_VARIABLE")
        for ((inputs, outputs, key) in groups) {
            when (command.value) {
                is Commands.Move -> {
                    val input = inputs.single()
                    requireThat {
                        "the transaction is signed by the owner of the CP" using (input.owner.owningKey in command.signers)
                        "the state is propagated" using (outputs.size == 1)
                        // Don't need to check anything else, as if outputs.size == 1 then the output is equal to
                        // the input ignoring the owner field due to the grouping.
                    }
                }

                is Commands.Redeem -> {
                    // Redemption of the paper requires movement of on-ledger cash.
                    val input = inputs.single()
                    val received = tx.outputStates.sumCashBy(input.owner)
                    val time = timeWindow?.fromTime ?: throw IllegalArgumentException("Redemptions must have a time-window")
                    requireThat {
                        "the paper must have matured" using (time >= input.maturityDate)
                        "the received amount equals the face value" using (received == input.faceValue)
                        "the paper must be destroyed" using outputs.isEmpty()
                        "the transaction is signed by the owner of the CP" using (input.owner.owningKey in command.signers)
                    }
                }

                is Commands.Issue -> {
                    val output = outputs.single()
                    val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Issuances have a time-window")
                    requireThat {
                        // Don't allow people to issue commercial paper under other entities identities.
                        "output states are issued by a command signer" using
                                (output.issuance.party.owningKey in command.signers)
                        "output values sum to more than the inputs" using (output.faceValue.quantity > 0)
                        "the maturity date is not in the past" using (time < output.maturityDate)
                        // Don't allow an existing CP state to be replaced by this issuance.
                        // TODO: Consider how to handle the case of mistaken issuances, or other need to patch.
                        "output values sum to more than the inputs" using inputs.isEmpty()
                    }
                }

            // TODO: Think about how to evolve contracts over time with new commands.
                else -> throw IllegalArgumentException("Unrecognised command")
            }
        }
    }
}
