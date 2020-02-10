#!/bin/sh

# There's no need to run as root, so don't allow it, for security reasons
if [ "$USER" = "root" ]; then
	echo "Please su to non-root user before running"
	exit
fi

# No qortal.jar but we have a Maven built one?
# Be helpful and copy across to correct location
if [ ! -e qortal.jar -a -f target/qortal*.jar ]; then
	echo "Copying Maven-built Qortal JAR to correct pathname"
	cp target/qortal*.jar qortal.jar
fi

# Limits Java JVM stack size and maximum heap usage.
# Comment out for bigger systems, e.g. non-routers
# or when API documentation is enabled
# JVM_MEMORY_ARGS="-Xss256k -Xmx128m"

# Although java.net.preferIPv4Stack is supposed to be false
# by default in Java 11, on some platforms (e.g. FreeBSD 12),
# it is overriden to be true by default. Hence we explicitly
# set it to true to obtain desired behaviour.
nohup nice -n 20 java \
	-Djava.net.preferIPv4Stack=false \
	-XX:NativeMemoryTracking=summary \
	${JVM_MEMORY_ARGS} \
	-jar qortal.jar \
	1>run.log 2>&1 &

# Save backgrounded process's PID
echo $! > run.pid
echo qortal running as pid $!
