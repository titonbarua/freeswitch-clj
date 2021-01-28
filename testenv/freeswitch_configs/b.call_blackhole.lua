-- caller_id = session:getVariable("caller_id_number");
-- dest_numb = session:getVariable("destination_number");
-- sip_host = session:getVariable("network_addr");
-- uuid = session:getVariable("uuid");

-- session:consoleLog(
--   "info",
--   "Handling call from " .. caller_id
--   .. " to " .. dest_numb
--   .. " made through host: " .. sip_host
--   .. " ...");

-- Initiate random number generator.
math.randomseed(os.time());

-- Let it dial for some time before ring, at max 1 second.
session:sleep(math.random(0, 1000));
session:preAnswer();

-- Let it ring for some time before answer, at max 2 second.
-- The call takes at most 3 seconds before answer.
session:sleep(math.random(0, 2000));
session:answer();

-- Answer the call.
session:execute("playback", "test_audio.mp3");

-- Hangup after some time, at max 5 second.
session:sleep(math.random(0, 5000));
session:hangup();
