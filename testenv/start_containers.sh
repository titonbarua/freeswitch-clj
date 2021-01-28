#!/bin/sh
set -x
set -e
TESTENV_DIR="$(pwd)/testenv"
# Start freeswitch host a.
# ESL HOST: 127.0.0.50, ::50
# ESL port: 8021
# LOGFILE: /tmp/freeswitch_clj_test_logs_a/freeswitch.log
docker run \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/a.modules.conf.xml",target=/etc/freeswitch/autoload_configs/modules.conf.xml \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/a.event_socket.conf.xml",target=/etc/freeswitch/autoload_configs/event_socket.conf.xml \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/a.logfile.conf.xml",target=/etc/freeswitch/autoload_configs/logfile.conf.xml \
        --volume /tmp/freeswitch_clj_test_logs_a:/var/log/ \
        --publish 8021:8021 \
        --rm \
        --detach \
        --network=host \
        --name freeswitch_clj_test_a \
        safarov/freeswitch

# Start freeswitch host b.
# ESL HOST: 127.0.0.51, ::51
# ESL port: 8022
# SIP PORT: 5080
# SIP user: callblackholeuser
# SIP pass: callblackholepass
# LOGFILE: /tmp/freeswitch_clj_test_logs_b/freeswitch.log
docker run \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/b.modules.conf.xml",target=/etc/freeswitch/autoload_configs/modules.conf.xml \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/b.event_socket.conf.xml",target=/etc/freeswitch/autoload_configs/event_socket.conf.xml \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/b.lua.conf.xml",target=/etc/freeswitch/autoload_configs/lua.conf.xml \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/b.sip_profile_external.xml",target=/etc/freeswitch/sip_profiles/external.xml \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/b.directory_call_blackhole.xml",target=/etc/freeswitch/directory/default/call_blackhole.xml \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/b.public_dialplan_call_blackhole.xml",target=/etc/freeswitch/dialplan/public/00_call_blackhole.xml \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/b.call_blackhole.lua",target=/usr/share/freeswitch/scripts/call_blackhole.lua \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/aesops-fables-023-the-father-and-his-sons.388.mp3",target=/usr/share/freeswitch/sounds/en/us/callie/test_audio.mp3 \
        --mount type=bind,readonly,source="$TESTENV_DIR/freeswitch_configs/b.logfile.conf.xml",target=/etc/freeswitch/autoload_configs/logfile.conf.xml \
        --volume /tmp/freeswitch_clj_test_logs_b:/var/log \
        --publish 8022:8022 \
        --publish 5080:5080 \
        --rm \
        --network=host \
        --name freeswitch_clj_test_b \
        safarov/freeswitch
