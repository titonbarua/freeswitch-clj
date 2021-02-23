caller_id = session:getVariable("caller_id_number");
dest_num = session:getVariable("destination_number");
-- sip_host = session:getVariable("network_addr");
-- uuid = session:getVariable("uuid");

-- Initiate random number generator.
math.randomseed(os.time());


if dest_num:sub(1, 6) == "555444" then
  -- Hangup from this side after five seconds.
  session:sleep(1000);
  session:answer();
  session:execute("playback", "test_audio.wav");
  -- Hangup from this side after five seconds.
  session:sleep(5000);
  session:hangup();

elseif dest_num:sub(1, 6) == "555555" then
    -- Wait for other side to hangup, upto 30 seconds.
    session:sleep(1000);
    session:answer();
    session:execute("playback", "test_audio.wav");
    session:sleep(30000);
    session:hangup();

else
  -- Let it dial for some time before ring, at max 1 second.
  session:sleep(math.random(0, 1000));
  session:preAnswer();

  -- Let it ring for some time before answer, at max 2 second.
  -- The call takes at most 3 seconds before answer.
  session:sleep(math.random(0, 2000));
  session:answer();

  -- Hangup after some time, at max 5 second.
  session:sleep(math.random(0, 5000));
  session:hangup();

end
