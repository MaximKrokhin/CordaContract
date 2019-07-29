# CordaContract
This is an implementation of commercial paper, which is essentially a simpler version of a corporate
bond. It can be seen as a company-specific currency. A company issues CP with a particular face value, say $100,
but sells it for less, say $90. The paper can be redeemed for cash at a given date in the future. Thus this example
would have a 10% interest rate with a single repayment. Commercial paper is often rolled over (the maturity date
is adjusted as if the paper was redeemed and immediately repurchased, but without having to front the cash).

This contract is not intended to realistically model CP. It is here only to act as a next step up above cash in
the prototyping phase. It is thus very incomplete.

Open issues:
 - In this model, you cannot merge or split CP. Can you do this normally? We could model CP as a specialised form
   of cash, or reuse some of the cash code? Waiting on response from Ayoub and Rajar about whether CP can always
   be split/merged or only in secondary markets. Even if current systems can't do this, would it be a desirable
   feature to have anyway?
 - The funding steps of CP is totally ignored in this model.
 - No attention is paid to the existing roles of custodians, funding banks, etc.
 - There are regional variations on the CP concept, for instance, American CP requires a special "CUSIP number"
   which may need to be tracked. That, in turn, requires validation logic (there is a bean validator that knows how
   to do this in the Apache BVal project).
 
