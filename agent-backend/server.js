const express = require("express");
const cors = require("cors");
const { spawn } = require("child_process");
const path = require("path");
const { listChats, getChat, createChat, appendMessage, deleteChat, updateChat } = require("./chat-store");
const {
  getSessionStatus,
  createSession,
  getSession,
  autoCommit,
  getSessionChanges,
  resetSession,
  acceptSession,
  discardSession,
  getVersions,
  getLog,
  rollbackMain,
  cleanupOrphans,
} = require("./git-ops");
const { resolveContext } = require("./context-map");

const app = express();
app.use(cors());
app.use(express.json({ limit: "1mb" }));

const PORT = 4100;
const REPO_ROOT = "/opt/egov/ccrs-dashboard";

function describeToolAction(tool, file) {
  const f = file ? ` ${file}` : "";
  switch (tool) {
    case "Read": return `Reading${f}`;
    case "Edit": return `Editing${f}`;
    case "Write": return `Writing${f}`;
    case "Glob": return `Searching files${f}`;
    case "Grep": return `Searching code${f}`;
    case "Bash": return `Running command${f}`;
    default:
      if (tool.startsWith("mcp__DIGIT")) {
        const mcpTool = tool.split("__").pop();
        return `Querying ${mcpTool}`;
      }
      return tool || "Working...";
  }
}

// Mutex: only one Claude subprocess at a time, also guards git state changes
let busy = false;
const queue = [];

// Event buffer — survives SSE disconnects, client polls to catch up
let eventBuffer = [];   // { id: number, data: object }
let eventSeq = 0;
let streamActive = false;

function bufferEvent(data) {
  eventSeq++;
  eventBuffer.push({ id: eventSeq, data, ts: Date.now() });
  // Keep last 500 events max
  if (eventBuffer.length > 500) eventBuffer = eventBuffer.slice(-500);
}

function clearEventBuffer() {
  eventBuffer = [];
  eventSeq = 0;
  streamActive = false;
}

function acquireLock() {
  return new Promise((resolve) => {
    if (!busy) {
      busy = true;
      resolve();
    } else {
      queue.push(resolve);
    }
  });
}

function releaseLock() {
  if (queue.length > 0) {
    queue.shift()();
  } else {
    busy = false;
  }
}

// --- Session endpoints ---

app.get("/api/agent/session", (req, res) => {
  res.json(getSessionStatus());
});

app.post("/api/agent/session/start", async (req, res) => {
  await acquireLock();
  try {
    res.json(createSession());
  } finally {
    releaseLock();
  }
});

app.post("/api/agent/session/accept", async (req, res) => {
  const { label } = req.body;
  if (!label) return res.status(400).json({ error: "label is required" });
  await acquireLock();
  try {
    res.json(acceptSession(label));
  } finally {
    releaseLock();
  }
});

app.post("/api/agent/session/discard", async (req, res) => {
  await acquireLock();
  try {
    res.json(discardSession());
  } finally {
    releaseLock();
  }
});

app.get("/api/agent/session/changes", (req, res) => {
  res.json(getSessionChanges());
});

app.post("/api/agent/session/rollback", async (req, res) => {
  const { commitHash } = req.body;
  if (!commitHash) return res.status(400).json({ error: "commitHash is required" });
  await acquireLock();
  try {
    res.json(resetSession(commitHash));
  } finally {
    releaseLock();
  }
});

// --- Versions endpoints ---

app.get("/api/agent/versions", (req, res) => {
  const limit = parseInt(req.query.limit) || 30;
  // Return merge commits if any, otherwise fall back to full log
  const versions = getVersions(limit);
  if (versions.length > 0) {
    res.json(versions);
  } else {
    // No merge commits yet — show full log so versions tab isn't empty
    res.json(getLog(limit));
  }
});

app.post("/api/agent/versions/rollback", async (req, res) => {
  const { commitHash } = req.body;
  if (!commitHash) return res.status(400).json({ error: "commitHash is required" });
  await acquireLock();
  try {
    res.json(rollbackMain(commitHash));
  } finally {
    releaseLock();
  }
});

// --- Chat persistence endpoints ---

app.get("/api/agent/chats", (req, res) => {
  res.json(listChats());
});

app.get("/api/agent/chats/:id", (req, res) => {
  const chat = getChat(req.params.id);
  if (!chat) return res.status(404).json({ error: "not found" });
  res.json(chat);
});

app.post("/api/agent/chats", (req, res) => {
  const { title } = req.body || {};
  res.json(createChat(title));
});

app.delete("/api/agent/chats/:id", (req, res) => {
  deleteChat(req.params.id);
  res.json({ success: true });
});

// --- Event polling (client catches up after reload) ---

app.get("/api/agent/stream/status", (req, res) => {
  res.json({ active: streamActive, eventCount: eventBuffer.length, lastEventId: eventSeq });
});

app.get("/api/agent/stream/events", (req, res) => {
  const since = parseInt(req.query.since) || 0;
  const events = eventBuffer.filter(e => e.id > since);
  res.json({ events: events.map(e => ({ id: e.id, ...e.data })), done: !streamActive });
});

// --- Chat endpoint ---

app.post("/api/agent/chat", async (req, res) => {
  const { message, context = {}, chatId } = req.body;
  if (!message) return res.status(400).json({ error: "message is required" });

  // Persist user message
  if (chatId) {
    appendMessage(chatId, { role: "user", content: message, timestamp: Date.now() });
  }

  // SSE headers
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
  });

  const sendEvent = (data) => {
    bufferEvent(data);
    try { res.write(`data: ${JSON.stringify(data)}\n\n`); } catch {}
  };

  await acquireLock();
  clearEventBuffer();
  streamActive = true;
  sendEvent({ type: "status", content: "Thinking..." });

  try {
    // Auto-create session branch if not active
    if (!getSession()) {
      const { branch } = createSession();
      sendEvent({ type: "session_created", branch });
    }

    // Resolve route context
    const routeContext = resolveContext(context.currentRoute || "");

    // Build conversation history
    const history = (context.conversationHistory || [])
      .map((m) => `${m.role === "user" ? "User" : "Assistant"}: ${m.content}`)
      .join("\n\n");

    const prompt = [
      "## Current Page Context",
      `Route: ${context.currentRoute || "unknown"}`,
      `Page: ${routeContext.description}`,
      "",
      "## Relevant Files (focus your edits here)",
      ...routeContext.relevantFiles.map((f) => `- ${f}`),
      "",
      "## Shared Files (for reference)",
      ...routeContext.sharedFiles.map((f) => `- ${f}`),
      "",
      history ? `## Conversation History\n${history}\n` : "",
      "## User Request",
      message,
    ]
      .filter(Boolean)
      .join("\n");

    // Spawn claude with DIGIT-MCP enabled
    const claude = spawn(
      "claude",
      [
        "-p",
        prompt,
        "--output-format",
        "stream-json",
        "--verbose",
        "--system-prompt-file",
        path.join(__dirname, "system-prompt.md"),
        "--mcp-config",
        path.join(REPO_ROOT, ".mcp.json"),
        "--strict-mcp-config",
        "--allowedTools",
        "Edit,Read,Write,Glob,Grep,Bash,mcp__DIGIT-MCP__pgr_search,mcp__DIGIT-MCP__pgr_create,mcp__DIGIT-MCP__mdms_search,mcp__DIGIT-MCP__user_search,mcp__DIGIT-MCP__workflow_process_search,mcp__DIGIT-MCP__health_check,mcp__DIGIT-MCP__db_counts,mcp__DIGIT-MCP__localization_search",
        "--max-turns",
        "15",
      ],
      {
        cwd: REPO_ROOT,
        env: { ...process.env },
        stdio: ["pipe", "pipe", "pipe"],
      }
    );

    let fullResponse = "";
    let buffer = "";
    // Collect all blocks for persistence (tool calls + text)
    const chatBlocks = [];

    claude.stdout.on("data", (chunk) => {
      buffer += chunk.toString();
      const lines = buffer.split("\n");
      buffer = lines.pop();

      for (const line of lines) {
        if (!line.trim()) continue;
        try {
          const event = JSON.parse(line);

          if (event.type === "assistant" && event.message) {
            for (const block of event.message.content || []) {
              if (block.type === "text") {
                sendEvent({ type: "new_block", blockType: "text" });
                fullResponse += block.text;
                chatBlocks.push({ role: "assistant", content: block.text });
                sendEvent({ type: "text", content: block.text });
              } else if (block.type === "thinking") {
                const snippet = (block.thinking || "").slice(0, 120);
                sendEvent({ type: "status", content: "Thinking...", detail: snippet });
              } else if (block.type === "tool_use") {
                const toolName = block.name || "";
                const input = block.input || {};
                const filePath = input.file_path || input.path || input.pattern || input.command || "";
                const shortPath = filePath.split("/").slice(-2).join("/");
                const label = describeToolAction(toolName, shortPath);
                chatBlocks.push({ role: "tool", content: label });
                sendEvent({ type: "new_block", blockType: "tool", label });
                sendEvent({ type: "tool_use", tool: toolName, label, file: shortPath });
              }
            }
          } else if (event.type === "content_block_delta") {
            if (event.delta?.type === "text_delta") {
              fullResponse += event.delta.text;
              sendEvent({ type: "text", content: event.delta.text });
            } else if (event.delta?.type === "thinking_delta") {
              const snippet = (event.delta.thinking || "").slice(0, 80);
              if (snippet.length > 10) {
                sendEvent({ type: "status", content: "Thinking...", detail: snippet });
              }
            }
          } else if (event.type === "content_block_start") {
            if (event.content_block?.type === "tool_use") {
              const toolName = event.content_block.name || "";
              sendEvent({ type: "tool_use", tool: toolName, label: describeToolAction(toolName, ""), file: "" });
            } else if (event.content_block?.type === "thinking") {
              sendEvent({ type: "status", content: "Thinking..." });
            }
          } else if (event.type === "result") {
            // Capture the final complete result for persistence
            if (event.result && !fullResponse) {
              fullResponse = event.result;
            }
          }
        } catch {
          // Not JSON, skip
        }
      }
    });

    claude.stderr.on("data", (chunk) => {
      const msg = chunk.toString();
      if (msg.includes("error") || msg.includes("Error")) {
        console.error("claude stderr:", msg);
      }
    });

    claude.on("close", (code) => {
      const shortMsg = message.length > 60 ? message.slice(0, 57) + "..." : message;
      const commitHash = autoCommit(`AI: ${shortMsg}`);

      // Persist all blocks (tool calls + text) as individual messages
      if (chatId) {
        if (chatBlocks.length > 0) {
          for (const block of chatBlocks) {
            appendMessage(chatId, {
              ...block,
              timestamp: Date.now(),
            });
          }
          // Tag the last block with commitHash
          if (commitHash) {
            const chat = require("./chat-store").getChat(chatId);
            if (chat && chat.messages.length > 0) {
              chat.messages[chat.messages.length - 1].commitHash = commitHash;
              require("./chat-store").updateChat(chatId, { messages: chat.messages });
            }
          }
        } else if (fullResponse) {
          appendMessage(chatId, { role: "assistant", content: fullResponse, commitHash, timestamp: Date.now() });
        }
      }

      sendEvent({
        type: "done",
        commitHash,
        exitCode: code,
      });

      streamActive = false;
      res.end();
      releaseLock();
    });

    claude.on("error", (err) => {
      sendEvent({ type: "error", content: err.message });
      res.end();
      releaseLock();
    });

    req.on("close", () => {
      claude.kill("SIGTERM");
      releaseLock();
    });
  } catch (err) {
    sendEvent({ type: "error", content: err.message });
    res.end();
    releaseLock();
  }
});

// --- PGR Stats (direct DB query, bypasses broken PGR search API) ---

app.get("/api/agent/pgr-stats", (req, res) => {
  try {
    const { execSync } = require("child_process");
    const raw = execSync(
      `docker exec docker-postgres psql -U egov -d egov -t -A -F'||' -c "
        SELECT servicecode, applicationstatus, count(*) as cnt,
               to_char(to_timestamp(createdtime/1000), 'YYYY-MM-DD') as dt
        FROM eg_pgr_service_v2
        WHERE tenantid = 'pg.citya'
        GROUP BY servicecode, applicationstatus, dt
        ORDER BY dt, cnt DESC;"`,
      { encoding: "utf-8" }
    ).trim();

    if (!raw) return res.json({ complaints: [], total: 0 });

    const rows = raw.split("\n").filter(Boolean).map((line) => {
      const [serviceCode, status, count, date] = line.split("||");
      return { serviceCode, status, count: parseInt(count), date };
    });

    const total = rows.reduce((s, r) => s + r.count, 0);
    res.json({ complaints: rows, total });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// --- Startup ---

// Clean up orphan sessions from previous runs
cleanupOrphans();

app.listen(PORT, "0.0.0.0", () => {
  console.log(`Agent backend running on http://0.0.0.0:${PORT}`);
  console.log(`Repo root: ${REPO_ROOT}`);
});
