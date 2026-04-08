const fs = require("fs");
const path = require("path");

const CHATS_DIR = path.join(__dirname, "chats");

// Ensure chats directory exists
if (!fs.existsSync(CHATS_DIR)) fs.mkdirSync(CHATS_DIR, { recursive: true });

function chatPath(id) {
  return path.join(CHATS_DIR, `${id}.json`);
}

function listChats() {
  try {
    const files = fs.readdirSync(CHATS_DIR).filter((f) => f.endsWith(".json"));
    return files
      .map((f) => {
        try {
          const data = JSON.parse(fs.readFileSync(path.join(CHATS_DIR, f), "utf-8"));
          return {
            id: data.id,
            title: data.title,
            messageCount: (data.messages || []).length,
            lastActivity: data.lastActivity,
            gitBranch: data.gitBranch || null,
          };
        } catch { return null; }
      })
      .filter(Boolean)
      .sort((a, b) => (b.lastActivity || 0) - (a.lastActivity || 0));
  } catch {
    return [];
  }
}

function getChat(id) {
  const p = chatPath(id);
  if (!fs.existsSync(p)) return null;
  try {
    return JSON.parse(fs.readFileSync(p, "utf-8"));
  } catch { return null; }
}

function createChat(title) {
  const id = `chat-${Date.now()}`;
  const chat = {
    id,
    title: title || "New Chat",
    messages: [],
    gitBranch: null,
    createdAt: Date.now(),
    lastActivity: Date.now(),
  };
  fs.writeFileSync(chatPath(id), JSON.stringify(chat, null, 2), "utf-8");
  return chat;
}

function updateChat(id, updates) {
  const chat = getChat(id);
  if (!chat) return null;
  Object.assign(chat, updates, { lastActivity: Date.now() });
  fs.writeFileSync(chatPath(id), JSON.stringify(chat, null, 2), "utf-8");
  return chat;
}

function appendMessage(id, message) {
  const chat = getChat(id);
  if (!chat) return null;
  chat.messages.push(message);
  chat.lastActivity = Date.now();
  // Auto-title from first user message if still default
  if (chat.title === "New Chat" && message.role === "user") {
    chat.title = message.content.slice(0, 50) + (message.content.length > 50 ? "..." : "");
  }
  fs.writeFileSync(chatPath(id), JSON.stringify(chat, null, 2), "utf-8");
  return chat;
}

function deleteChat(id) {
  const p = chatPath(id);
  if (fs.existsSync(p)) {
    fs.unlinkSync(p);
    return true;
  }
  return false;
}

module.exports = { listChats, getChat, createChat, updateChat, appendMessage, deleteChat };
