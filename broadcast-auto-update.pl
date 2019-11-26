#!/usr/bin/env perl

use POSIX;
use Getopt::Std;

sub usage() {
	die("usage: $0 [-p port] dev-private-key\n");
}

my %opt;
getopts('p:', \%opt);

usage() unless @ARGV == 1;

my $port = $opt{p} || 12391;
my $privkey = shift @ARGV;

open(POM, '<', 'pom.xml') || die ("Can't open 'pom.xml': $!\n");
my $project;
while (<POM>) {
	if (m/<artifactId>(\w+)<.artifactId>/o) {
		$project = $1;
		last;
	}
}
close(POM);

open(PROPS, '-|', 'unzip -p target/${project}*.jar build.properties') || die("Can't extract 'build.properties' from JAR: $!\n");
while (<PROPS>) {
	if (m/build.timestamp=(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})Z/o) {
		$timestamp = strftime('%s', $6, $5, $4, $3, $2 - 1, $1 - 1900, 0, 0, 0) * 1000;
		last;
	}
}
close(PROPS);

die("Can't process build.timestamp\n") if ! defined $timestamp;

$commit_hash = `git show --no-patch --format=%H`;

die("Can't find commit hash\n") if ! defined $commit_hash;
chomp $commit_hash;

$sha256sum = `sha256sum ${project}.update 2>/dev/null || sha256 ${project}.update 2>/dev/null`;

die("Can't calculate SHA256 of ${project}.update\n") unless $sha256sum =~ m/(\S{64})/;

$sha256 = $1;

printf "Build timestamp (ms): %d / 0x%016x\n", $timestamp, $timestamp;
printf "Commit hash: %s\n", $commit_hash;
printf "SHA256 of ${project}.update: %s\n", $sha256;

$tx_type = 10;
$tx_timestamp = time() * 1000;
$tx_group_id = 1;
$service = 1;
printf "\nARBITRARY(%d) transaction with timestamp %d, txGroupID %d and service %d\n", $tx_type, $tx_timestamp, $tx_group_id, $service;

$data = sprintf "%016x%s%s", $timestamp, $commit_hash, $sha256;
printf "\nARBITRARY transaction data payload: %s\n", $data;

$n_payments = 0;
$is_raw = 1; # RAW_DATA
$data_length = length($data) / 2;
$fee = 0.001 * 1e8;

my $pubkey = `curl --silent --url http://localhost:${port}/utils/publickey --data ${privkey}`;
die("Can't convert private key to public key!\n") unless $pubkey;
printf "\nPublic key: %s\n", $pubkey;

my $pubkey_hex = `curl --silent --url http://localhost:${port}/utils/frombase58 --data ${pubkey}`;
printf "Public key hex: %s\n", $pubkey_hex;

my $address = `curl --silent --url http://localhost:${port}/addresses/convert/${pubkey}`;
printf "Address: %s\n", $address;

my $reference = `curl --silent --url http://localhost:${port}/addresses/lastreference/${address}`;
printf "Last reference: %s\n", $reference;

my $reference_hex = `curl --silent --url http://localhost:${port}/utils/frombase58 --data ${reference}`;
printf "Last reference hex: %s\n", $reference_hex;

my $raw_tx_hex = sprintf("%08x%016x%08x%s%s%08x%08x%02x%08x%s%016x", $tx_type, $tx_timestamp, $tx_group_id, $reference_hex, $pubkey_hex, $n_payments, $service, $is_raw, $data_length, $data, $fee);
printf "\nRaw transaction hex:\n%s\n", $raw_tx_hex;

my $raw_tx = `curl --silent --url http://localhost:${port}/utils/tobase58/${raw_tx_hex}`;
printf "\nRaw transaction (base58):\n%s\n", $raw_tx;

my $sign_data = qq|' { "privateKey": "${privkey}", "transactionBytes": "${raw_tx}" } '|;
my $signed_tx = `curl --silent -H "accept: text/plain" -H "Content-Type: application/json" --url http://localhost:${port}/transactions/sign --data ${sign_data}`;
printf "\nSigned transaction:\n%s\n", $signed_tx;

# Flush STDOUT after every output
$| = 1;
print "\n";
for (my $delay = 5; $delay > 0; --$delay) {
	printf "\rSubmitting transaction in %d second%s... CTRL-C to abort ", $delay, ($delay != 1 ? 's' : '');
	sleep 1;
}

printf "\rSubmitting transaction NOW...                                    \n";
my $result = `curl --silent --url http://localhost:${port}/transactions/process --data ${signed_tx}`;
printf "\nTransaction accepted: %s\n", $result;
