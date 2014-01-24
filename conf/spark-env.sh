#!/usr/bin/env bash

# This file contains environment variables required to run Spark. Copy it as
# spark-env.sh and edit that to configure Spark for your site.
#
# The following variables can be set in this file:
# - SPARK_LOCAL_IP, to set the IP address Spark binds to on this node
# - MESOS_NATIVE_LIBRARY, to point to your libmesos.so if you use Mesos
# - SPARK_JAVA_OPTS, to set node-specific JVM options for Spark. Note that
#   we recommend setting app-wide options in the application's driver program.
#     Examples of node-specific options : -Dspark.local.dir, GC options
#     Examples of app-wide options : -Dspark.serializer
#
# If using the standalone deploy mode, you can also set variables for it here:
# - SPARK_MASTER_IP, to bind the master to a different IP address or hostname
# - SPARK_MASTER_PORT / SPARK_MASTER_WEBUI_PORT, to use non-default ports
# - SPARK_WORKER_CORES, to set the number of cores to use on this machine
# - SPARK_WORKER_MEMORY, to set how much memory to use (e.g. 1000m, 2g)
# - SPARK_WORKER_PORT / SPARK_WORKER_WEBUI_PORT
# - SPARK_WORKER_INSTANCES, to set the number of worker processes per node

#export SPARK_MASTER_IP=172.16.126.183
export SPARK_WORKER_MEMORY=10g
export SPARK_MEM=8g
#export SPARK_MASTER=yama@172.16.126.183:/home/yama/bdas/spark-bsp

#export SPARK_JAVA_OPTS+=" -Dspark.gradientDescent.innerIteration=20"

# This is for trigram web spam dataset
#export SPARK_JAVA_OPTS+=" -Dspark.default.dimension=16609144"

# This is for unigram web spam dataset
# export SPARK_JAVA_OPTS+=" -Dspark.default.dimension=255"


# - to use YJP

# export SPARK_JAVA_OPTS+=" -agentpath:/opt/yjp-2013-build-13068/bin/linux-x86-64/libyjpagent.so=onexit=snapshot,sampling,dir=/home/yama/yjp/snapshots,logdir=/home/yama/yjp/logs"
