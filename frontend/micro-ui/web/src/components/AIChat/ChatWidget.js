import React, { useState, useRef, useEffect, useCallback } from "react";
import VersionPanel from "./VersionPanel";
import "./chatWidget.css";

const AGENT_API = "/api/agent";

const ChatWidget = () => {
  const [isOpen, setIsOpen] = useState(false);
  const [activeTab, setActiveTab] = useState("chat"); // "chat" | "versions"
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [currentTool, setCurrentTool] = useState(null);
  const messagesEndRef = useRef(null);
  const inputRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  useEffect(() => {
    if (isOpen && inputRef.current) {
      inputRef.current.focus();
    }
  }, [isOpen]);

  const getCurrentRoute = () => {
    return window.location.pathname;
  };

  const sendMessage = useCallback(async () => {
    if (!input.trim() || isStreaming) return;

    const userMessage = input.trim();
    setInput("");
    setIsStreaming(true);
    setCurrentTool(null);

    // Add user message
    const newMessages = [...messages, { role: "user", content: userMessage }];
    setMessages(newMessages);

    // Add placeholder for assistant
    const assistantIdx = newMessages.length;
    setMessages((prev) => [...prev, { role: "assistant", content: "", status: "streaming" }]);

    try {
      const response = await fetch(`${AGENT_API}/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message: userMessage,
          context: {
            currentRoute: getCurrentRoute(),
            conversationHistory: newMessages.slice(-10).map((m) => ({
              role: m.role,
              content: m.content,
            })),
          },
        }),
      });

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop();

        for (const line of lines) {
          if (!line.startsWith("data: ")) continue;
          try {
            const event = JSON.parse(line.slice(6));

            if (event.type === "text") {
              setMessages((prev) => {
                const updated = [...prev];
                updated[assistantIdx] = {
                  ...updated[assistantIdx],
                  content: updated[assistantIdx].content + event.content,
                };
                return updated;
              });
            } else if (event.type === "tool_use") {
              setCurrentTool(event.label || event.tool);
            } else if (event.type === "status") {
              setCurrentTool(event.detail ? `${event.content} ${event.detail}` : event.content);
            } else if (event.type === "done") {
              setMessages((prev) => {
                const updated = [...prev];
                updated[assistantIdx] = {
                  ...updated[assistantIdx],
                  status: "done",
                  commitHash: event.commitHash,
                };
                return updated;
              });
              setCurrentTool(null);
            } else if (event.type === "error") {
              setMessages((prev) => {
                const updated = [...prev];
                updated[assistantIdx] = {
                  ...updated[assistantIdx],
                  content: updated[assistantIdx].content + "\n\nError: " + event.content,
                  status: "error",
                };
                return updated;
              });
            }
          } catch {}
        }
      }
    } catch (err) {
      setMessages((prev) => {
        const updated = [...prev];
        updated[assistantIdx] = {
          role: "assistant",
          content: "Connection error: " + err.message,
          status: "error",
        };
        return updated;
      });
    }

    setIsStreaming(false);
    setCurrentTool(null);
  }, [input, isStreaming, messages]);

  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  if (!isOpen) {
    return (
      <button className="ccrs-chat-fab" onClick={() => setIsOpen(true)} title="AI Editor">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
      </button>
    );
  }

  return (
    <div className="ccrs-chat-panel">
      {/* Header */}
      <div className="ccrs-chat-header">
        <div className="ccrs-chat-tabs">
          <button
            className={`ccrs-tab ${activeTab === "chat" ? "active" : ""}`}
            onClick={() => setActiveTab("chat")}
          >
            Chat
          </button>
          <button
            className={`ccrs-tab ${activeTab === "versions" ? "active" : ""}`}
            onClick={() => setActiveTab("versions")}
          >
            Versions
          </button>
        </div>
        <button className="ccrs-chat-close" onClick={() => setIsOpen(false)}>
          &times;
        </button>
      </div>

      {activeTab === "versions" ? (
        <VersionPanel />
      ) : (
        <>
          {/* Messages */}
          <div className="ccrs-chat-messages">
            {messages.length === 0 && (
              <div className="ccrs-chat-empty">
                Describe a UI change and I'll edit the code live.
                <br />
                <span className="ccrs-chat-hint">
                  e.g. "add a status column to the inbox table"
                </span>
              </div>
            )}
            {messages.map((msg, i) => (
              <div key={i} className={`ccrs-msg ccrs-msg-${msg.role}`}>
                <div className="ccrs-msg-content">
                  {msg.content || (msg.status === "streaming" ? "..." : "")}
                </div>
                {msg.commitHash && (
                  <div className="ccrs-msg-commit">Applied [{msg.commitHash}]</div>
                )}
              </div>
            ))}
            <div ref={messagesEndRef} />
          </div>

          {/* Status bar */}
          {(isStreaming || currentTool) && (
            <div className="ccrs-chat-status">
              <span className="ccrs-spinner" />
              <span className="ccrs-status-text">{currentTool || "Thinking..."}</span>
            </div>
          )}

          {/* Input */}
          <div className="ccrs-chat-input-area">
            <textarea
              ref={inputRef}
              className="ccrs-chat-input"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Describe a UI change..."
              rows={2}
              disabled={isStreaming}
            />
            <button
              className="ccrs-chat-send"
              onClick={sendMessage}
              disabled={isStreaming || !input.trim()}
            >
              Send
            </button>
          </div>
        </>
      )}
    </div>
  );
};

export default ChatWidget;
