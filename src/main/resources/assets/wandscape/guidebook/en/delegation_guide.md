# Delegation

Delegation pins one mage to one building. The Item Workshop, Equipment Workshop, Magic Workshop and Element Node panels each carry a Delegate button in their lower right corner — press it and pick a mage.

## The Three Rules

- That mage works **only on this building**: it takes no other building's jobs, and no job that belongs to no building either (guarding, altar casting).
- This building's jobs go **only to that mage**: while it is busy, following you, or too far away, the job waits in the queue instead of going to someone else.
- **A mage serves one building at a time**: delegating it to another building moves it off its old post.

## How to Use It

Press Delegate to open the mage list. Each row says what that mage is doing right now (Idle / Working / Following a player), and a row already assigned somewhere else names that building — clicking it moves the mage here.

To put the building back to "any mage may take its jobs", open the list again and press Clear Delegation.

## When It Pays Off

Assign a mage when you want one workshop to keep working, or a node to keep producing, without other buildings stealing the worker.

Be careful in a small town: a delegated mage takes no guard jobs and will not help with construction, so one post locks up one mage.

## Things to Know

- The mage **does not teleport** — it walks there. If the mage's chunk is not loaded, this building's jobs keep waiting for it.
- A change is **not instant**: a job already running is returned to the queue on the next tick and picked up again right away, restarting from its material-fetch step.
- If the mage dies or is dismissed, the delegation is released and the building takes any mage again. A chunk unload does **not** release it — the mage comes back, so the post is kept.
- Demolishing or undoing the building takes the delegation with it.
