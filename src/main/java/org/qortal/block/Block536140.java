package org.qortal.block;

import org.qortal.naming.Name;
import org.qortal.repository.DataException;

/**
 * Block 536140
 * <p>
 * This block had the same problem as block 535658.
 * <p>
 * Original transaction:
 * <code><pre>
 {
     "type": "REGISTER_NAME",
     "timestamp": 1630701955448,
     "reference": "5CytqtRzhP1irQjiJfKBwNkKBVM9gfvkWQEwqT49VNAofcyNHtSpqrVKB9v44NkhxytHwvfneCndCQTp3J8wU9p7",
     "fee": "0.00100000",
     "signature": "sPhiAfQ7MenpJAarTZ99neQHBrmyQ3jDFxRp79BTDmkRf7fMsQinuZJvWbsCzGeihr6zEjuPCD2k9srNGkzLhSS",
     "txGroupId": 0,
     "blockHeight": 535172,
     "approvalStatus": "NOT_REQUIRED",
     "creatorAddress": "QSUnyUZugWanhDtPaySLdaAGyKLzN3SurS",
     "registrantPublicKey": "C83r2taaX3pGQTgjmb7QNnFN8GWJqZxnhwptJEViJSqM",
     "name": "Qweb",
     "data": "{\"age\":30}"
 }
 </pre></code>
 * <p>
 * Duplicate transaction:
 * <code><pre>
 {
	 "type": "REGISTER_NAME",
	 "timestamp": 1630777397713,
	 "reference": "sPhiAfQ7MenpJAarTZ99neQHBrmyQ3jDFxRp79BTDmkRf7fMsQinuZJvWbsCzGeihr6zEjuPCD2k9srNGkzLhSS",
	 "fee": "0.00100000",
	 "signature": "45knBoCoKxraJaJWuwANTyM75Su9TAz45bvU8mQLj9wxwNvkVwrFXneLQtiNzN6ctcmNcGLTR4npiJ7PdxtxbJQA",
	 "txGroupId": 0,
	 "blockHeight": 536140,
	 "approvalStatus": "NOT_REQUIRED",
	 "creatorAddress": "QSUnyUZugWanhDtPaySLdaAGyKLzN3SurS",
	 "registrantPublicKey": "C83r2taaX3pGQTgjmb7QNnFN8GWJqZxnhwptJEViJSqM",
	 "name": "Qweb",
	 "data": "Registered Name on the Qortal Chain"
 }
 </pre></code>
 */
public final class Block536140 {

	private Block536140() {
		/* Do not instantiate */
	}

	public static void processFix(Block block) throws DataException {
		// Unregister the existing name record if it exists
		// This ensures that the duplicate name is considered valid, and therefore
		// the second (i.e. duplicate) REGISTER_NAME transaction data is applied.
		// Both were issued by the same user account, so there is no conflict.
		Name name = new Name(block.repository, "Qweb");
		name.unregister();
	}

}
