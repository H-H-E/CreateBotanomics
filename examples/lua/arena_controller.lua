-- Place a computer next to the Battle Transponder on your assembled machine.
-- Connect the computer's back output to your existing Create/Simulated controls.
-- This conservative example enables that output only during an active match.
-- Change the output side for your own wiring. Never power the same input manually
-- in an autonomous competition.

local match = assert(peripheral.find("battle_transponder"), "No Battle Transponder connected")
local output = "back"

local function run()
  while true do
    local active = match.getState() == "active"
    redstone.setOutput(output, active)
    term.clear()
    term.setCursorPos(1, 1)
    print("Arena: " .. match.getArena())
    print("State: " .. match.getState())
    print(string.format("Time: %.1fs", match.getTimeRemaining()))
    local score = match.getScore()
    print("Score: " .. tostring(score.total or 0))
    local hits = match.getRecentHits()
    print("Recent hits: " .. tostring(#hits))
    sleep(0.25)
  end
end

local ok, message = pcall(run)
redstone.setOutput(output, false)
if not ok then printError(message) end
