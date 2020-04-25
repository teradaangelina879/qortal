package org.qortal.test;

public class AccountRefCacheTests {

	// Test no cache in play (existing account):
	// fetch 1st ref
	// generate 2nd ref and call Account.setLastReference
	// fetch 3rd ref
	// 3rd ref should match 2st ref

	// Test no cache in play (no account):
	// fetch 1st ref
	// generate 2nd ref and call Account.setLastReference
	// fetch 3rd ref
	// 3rd ref should match 2st ref

	// Test cache in play (existing account, no commit):
	// fetch 1st ref
	// begin caching
	// fetch 2nd ref
	// 2nd ref should match 1st ref
	// generate 3rd ref and call Account.setLastReference
	// fetch 4th ref
	// 4th ref should match 1st ref
	// discard cache
	// fetch 5th ref
	// 5th ref should match 1st ref

	// Test cache in play (existing account, with commit):
	// fetch 1st ref
	// begin caching
	// fetch 2nd ref
	// 2nd ref should match 1st ref
	// generate 3rd ref and call Account.setLastReference
	// fetch 4th ref
	// 4th ref should match 1st ref
	// commit cache
	// fetch 5th ref
	// 5th ref should match 3rd ref

	// Test cache in play (new account, no commit):
	// fetch 1st ref (null)
	// begin caching
	// fetch 2nd ref
	// 2nd ref should match 1st ref
	// generate 3rd ref and call Account.setLastReference
	// fetch 4th ref
	// 4th ref should match 1st ref
	// discard cache
	// fetch 5th ref
	// 5th ref should match 1st ref

	// Test cache in play (new account, with commit):
	// fetch 1st ref (null)
	// begin caching
	// fetch 2nd ref
	// 2nd ref should match 1st ref
	// generate 3rd ref and call Account.setLastReference
	// fetch 4th ref
	// 4th ref should match 1st ref
	// commit cache
	// fetch 5th ref
	// 5th ref should match 3rd ref

	// Test Block support
	// fetch 1st ref for Alice
	// generate new payment from Alice to new account Ellen
	// generate another payment from Alice to new account Ellen
	// mint block containing payments
	// confirm Ellen's ref is 1st payment's sig
	// confirm Alice's ref if 2nd payment's sig
	// orphan block
	// confirm Ellen's ref is null
	// confirm Alice's ref matches 1st ref

}
