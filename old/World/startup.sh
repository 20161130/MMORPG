#!/bin/bash

cd /home/allWorld
DATESTR=`date +%Y%m%d`
rm -f stdout.log.$DATESTR
mv stdout.log stdout.log.$DATESTR

#tail -f stdout.log

LIB_PATH=./lib/antlr-2.7.6.jar:./lib/asm-3.1.jar:./lib/commons-httpclient-3.1.jar
LIB_PATH=$LIB_PATH:./lib/c3p0-0.9.1.jar
LIB_PATH=$LIB_PATH:./lib/cglib-2.2.jar:./lib/commons-beanutils-core-1.8.0.jar:./lib/commons-codec-1.3.jar:./lib/commons-collections-3.2.jar:./lib/commons-configuration-1.6.jar
LIB_PATH=$LIB_PATH:./lib/commons-digester-1.8.jar:./lib/commons-lang-2.4.jar:./lib/commons-logging-1.1.1.jar:./lib/commons-pool-1.5.6.jar
LIB_PATH=$LIB_PATH:./lib/dom4j-1.6.1.jar:./lib/hibernate3.jar:./lib/ejb3-persistence.jar:./lib/ezmorph-1.0.3.jar:./lib/json-lib-2.4-jdk15.jar
LIB_PATH=$LIB_PATH:./lib/jta.jar:./lib/log4j-1.2.15.jar:./lib/mina-core-1.1.7.jar:./lib/mysql-connector-java-5.0.8-bin.jar
LIB_PATH=$LIB_PATH:./lib/slf4j-api-1.6.0.jar:./lib/slf4j-log4j12-1.6.1.jar:./lib/trove-3.0.0a5.jar:./lib/log4j-1.2.15.jar
LIB_PATH=$LIB_PATH:./lib/commons-primitives-1.0.jar:./lib/ehcache-core-2.4.2.jar:./lib/java_memcached-release_2.5.1.jar
LIB_PATH=$LIB_PATH:./lib/serverengine.jar:./lib/javassist.jar

$JAVA_HOME/bin/java -server -Xms500m -Xmx500m -XX:ThreadPriorityPolicy=42 -verbose:gc -XX:PermSize=128m -XX:MaxPermSize=128m -XX:+PrintGCDetails  -XX:+PrintGCTimeStamps  -XX:+UseFastAccessorMethods -XX:CMSInitiatingOccupancyFraction=80 -XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled -XX:+UseCMSCompactAtFullCollection -XX:MaxTenuringThreshold=31 -XX:+DisableExplicitGC -Djava.awt.headless=true -classpath "./lib/world.jar:$CLASSPATH:.:$LIB_PATH" cyou.akworld.World $@>> stdout.log 2>&1 &

echo $! > allworld.pid
tail -f stdout.log

