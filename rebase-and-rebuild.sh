#!/usr/bin/env bash

set -e

commit_msg="Rebased, XORed, auto-update JAR"

project=$( perl -n -e 'if (m/<artifactId>(\w+)<.artifactId>/) { print $1; exit }' pom.xml $)

echo 'Checking for previous JAR commit to remove'
top_commit=$(git log -n 1 --format=%s)
if [ "${top_commit}" = "${commit_msg}" ]; then
	echo 'Removing previous JAR commit'
	git reset --hard HEAD^
	git push --force-with-lease
fi

echo 'Rebasing using master branch'
git fetch -p origin
git rebase origin/master

echo 'Pushing rebased branch'
git push --force-with-lease

echo 'Building new XORed auto-update JAR'
mvn clean
mvn package
java -cp target/${project}*.jar org.qora.XorUpdate target/${project}*.jar ${project}.update

echo 'Pushing new JAR commit'
git add ${project}.update
git commit -m "${commit_msg}"
git push
