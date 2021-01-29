#!/bin/sh
set -x
./testenv/stop_containers.sh
./testenv/start_containers.sh && lein test
./testenv/stop_containers.sh
