#!/bin/sh
set -x
./testenv/start_containers.sh && lein test
./testenv/stop_containers.sh
