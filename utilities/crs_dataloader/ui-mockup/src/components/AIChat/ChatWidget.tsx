import { useState, useRef, useEffect, useCallback } from 'react';
import './chatWidget.css';
import VersionPanel from './VersionPanel';

const AGENT_API = '/api/agent';

// --- Markdown renderer ---
function renderMarkdown(text: string) {
  if (!text) return null;
  const lines = text.split('\n');
  const elements: JSX.Element[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.startsWith('```')) {
      const codeLines: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) { codeLines.push(lines[i]); i++; }
      i++;
      elements.push(<pre key={elements.length} className="ccrs-code-block"><code>{codeLines.join('\n')}</code></pre>);
      continue;
    }
    if (line.startsWith('### ')) { elements.push(<div key={elements.length} className="ccrs-md-h3">{inlineFmt(line.slice(4))}</div>); i++; continue; }
    if (line.startsWith('## ')) { elements.push(<div key={elements.length} className="ccrs-md-h2">{inlineFmt(line.slice(3))}</div>); i++; continue; }
    if (line.startsWith('# ')) { elements.push(<div key={elements.length} className="ccrs-md-h1">{inlineFmt(line.slice(2))}</div>); i++; continue; }
    if (line.match(/^[-*] /)) { elements.push(<div key={elements.length} className="ccrs-md-li">{inlineFmt(line.slice(2))}</div>); i++; continue; }
    if (line.match(/^\d+\. /)) { elements.push(<div key={elements.length} className="ccrs-md-li">{inlineFmt(line.replace(/^\d+\.\s*/, ''))}</div>); i++; continue; }
    if (!line.trim()) { elements.push(<div key={elements.length} className="ccrs-md-br" />); i++; continue; }
    elements.push(<div key={elements.length} className="ccrs-md-p">{inlineFmt(line)}</div>); i++;
  }
  return <>{elements}</>;
}

function inlineFmt(text: string): (string | JSX.Element)[] {
  const parts: (string | JSX.Element)[] = [];
  const regex = /(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*)/g;
  let lastIdx = 0;
  let match: RegExpExecArray | null;
  while ((match = regex.exec(text)) !== null) {
    if (match.index > lastIdx) parts.push(text.slice(lastIdx, match.index));
    const m = match[0];
    if (m.startsWith('`')) parts.push(<code key={match.index} className="ccrs-inline-code">{m.slice(1, -1)}</code>);
    else if (m.startsWith('**')) parts.push(<strong key={match.index}>{m.slice(2, -2)}</strong>);
    else if (m.startsWith('*')) parts.push(<em key={match.index}>{m.slice(1, -1)}</em>);
    lastIdx = match.index + m.length;
  }
  if (lastIdx < text.length) parts.push(text.slice(lastIdx));
  return parts;
}

// --- Types ---
interface Message { role: string; content: string; status?: string; commitHash?: string | null; }
interface ChatSession { id: string; title: string; messageCount: number; lastActivity: number; }
interface SessionStatus { active: boolean; branch: string | null; }

export default function ChatWidget() {
  const [isOpen, setIsOpen] = useState(() => {
    try { return localStorage.getItem('ccrs-chat-open') === 'true'; } catch { return false; }
  });
  const [mainTab, setMainTab] = useState<'chat' | 'changes' | 'versions'>(() => {
    try { return (localStorage.getItem('ccrs-chat-tab') as any) || 'chat'; } catch { return 'chat'; }
  });

  // Multi-chat state
  const [chatList, setChatList] = useState<ChatSession[]>([]);
  const [activeChatId, setActiveChatId] = useState<string | null>(() => {
    try { return localStorage.getItem('ccrs-active-chat'); } catch { return null; }
  });
  const [messages, setMessages] = useState<Message[]>([]);

  const [input, setInput] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [currentTool, setCurrentTool] = useState<string | null>(null);
  const [session, setSession] = useState<SessionStatus>({ active: false, branch: null });
  const [changes, setChanges] = useState<Array<{ hash: string; message: string; date: string }>>([]);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);
  useEffect(() => { if (isOpen && inputRef.current && mainTab === 'chat') inputRef.current.focus(); }, [isOpen, mainTab]);

  // Persist open/tab state
  useEffect(() => { try { localStorage.setItem('ccrs-chat-open', String(isOpen)); } catch {} }, [isOpen]);
  useEffect(() => { try { localStorage.setItem('ccrs-chat-tab', mainTab); } catch {} }, [mainTab]);

  // --- Data fetching ---
  const fetchSession = useCallback(async () => {
    try { const r = await fetch(`${AGENT_API}/session`); const d = await r.json(); setSession({ active: d.active, branch: d.branch }); } catch {}
  }, []);

  const fetchChanges = useCallback(async () => {
    try { const r = await fetch(`${AGENT_API}/session/changes`); setChanges(await r.json()); } catch {}
  }, []);

  const fetchChatList = useCallback(async () => {
    try { const r = await fetch(`${AGENT_API}/chats`); setChatList(await r.json()); } catch {}
  }, []);

  const lastEventIdRef = useRef(0);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadChat = useCallback(async (chatId: string) => {
    try {
      const r = await fetch(`${AGENT_API}/chats/${chatId}`);
      if (!r.ok) {
        // Chat was deleted — clear stale reference, create fresh
        localStorage.removeItem('ccrs-active-chat');
        setActiveChatId(null);
        setMessages([]);
        return;
      }
      const data = await r.json();
      setActiveChatId(chatId);
      localStorage.setItem('ccrs-active-chat', chatId);
      setMessages((data.messages || []).map((m: any) => ({
        role: m.role,
        content: m.content || '',
        commitHash: m.commitHash || null,
        status: 'done',
      })));
    } catch {
      localStorage.removeItem('ccrs-active-chat');
      setActiveChatId(null);
      setMessages([]);
    }
  }, []);

  // Poll for events after reconnect (HMR reload)
  // Rebuilds messages from the full event buffer — replaces messages, never appends duplicates
  const startPolling = useCallback((baseMessages: Message[]) => {
    if (pollTimerRef.current) return;
    setIsStreaming(true);

    // Snapshot the persisted messages as the base — poll events append on top
    const base = [...baseMessages];
    let lastId = 0;

    pollTimerRef.current = setInterval(async () => {
      try {
        const r = await fetch(`${AGENT_API}/stream/events?since=${lastId}`);
        const data = await r.json();

        if (data.events.length > 0) {
          // Rebuild streamed messages from ALL events so far
          const streamed: Message[] = [];
          for (const evt of data.events) {
            lastId = evt.id;
            if (evt.type === 'new_block') {
              const role = evt.blockType === 'tool' ? 'tool' : 'assistant';
              const content = evt.blockType === 'tool' ? (evt.label || '') : '';
              streamed.push({ role, content, status: 'streaming' });
            } else if (evt.type === 'text') {
              const last = streamed[streamed.length - 1];
              if (last && last.role === 'assistant') {
                last.content += evt.content;
              }
            } else if (evt.type === 'tool_use') {
              setCurrentTool(evt.label || evt.tool);
            } else if (evt.type === 'status') {
              setCurrentTool(evt.detail ? `${evt.content} ${evt.detail}` : evt.content);
            } else if (evt.type === 'done') {
              const last = streamed[streamed.length - 1];
              if (last) { last.status = 'done'; last.commitHash = evt.commitHash; }
              setCurrentTool(null);
            }
          }
          // Set messages = persisted base + live streamed
          setMessages([...base, ...streamed]);
        }

        if (data.done && pollTimerRef.current) {
          clearInterval(pollTimerRef.current);
          pollTimerRef.current = null;
          setIsStreaming(false);
          setCurrentTool(null);
          fetchChanges();
          fetchSession();
          fetchChatList();
        }
      } catch {}
    }, 500);
  }, [fetchChanges, fetchSession, fetchChatList]);

  // Stop polling on unmount
  useEffect(() => {
    return () => { if (pollTimerRef.current) clearInterval(pollTimerRef.current); };
  }, []);

  // On mount + open: restore chat and check for active stream
  useEffect(() => {
    if (!isOpen) return;

    fetchSession();
    fetchChanges();
    fetchChatList();

    const savedId = activeChatId || localStorage.getItem('ccrs-active-chat');

    const restore = async () => {
      // Load the saved chat or the most recent one
      let chatToLoad = savedId;
      if (!chatToLoad) {
        try {
          const listRes = await fetch(`${AGENT_API}/chats`);
          const list = await listRes.json();
          if (list.length > 0) chatToLoad = list[0].id;
        } catch {}
      }
      if (chatToLoad) {
        await loadChat(chatToLoad);
      }

      // Check if there's an active stream to catch up on
      try {
        const statusRes = await fetch(`${AGENT_API}/stream/status`);
        const status = await statusRes.json();
        if (status.active && chatToLoad) {
          const chatRes = await fetch(`${AGENT_API}/chats/${chatToLoad}`);
          const chatData = await chatRes.json();
          const base = (chatData.messages || []).map((m: any) => ({
            role: m.role, content: m.content || '', commitHash: m.commitHash || null, status: 'done' as const,
          }));
          startPolling(base);
        }
      } catch {}
    };

    restore();
  }, [isOpen]); // eslint-disable-line

  const createNewChat = async () => {
    try {
      const r = await fetch(`${AGENT_API}/chats`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
      const chat = await r.json();
      setChatList(prev => [{ id: chat.id, title: chat.title, messageCount: 0, lastActivity: chat.lastActivity }, ...prev]);
      setActiveChatId(chat.id);
      localStorage.setItem('ccrs-active-chat', chat.id);
      setMessages([]);
    } catch {}
  };

  const closeChat = async (chatId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    await fetch(`${AGENT_API}/chats/${chatId}`, { method: 'DELETE' });
    setChatList(prev => prev.filter(c => c.id !== chatId));
    if (activeChatId === chatId) {
      const remaining = chatList.filter(c => c.id !== chatId);
      if (remaining.length > 0) { loadChat(remaining[0].id); }
      else { setActiveChatId(null); localStorage.removeItem('ccrs-active-chat'); setMessages([]); }
    }
  };

  // --- Send message ---
  const sendMessage = useCallback(async () => {
    if (!input.trim() || isStreaming) return;
    const userMessage = input.trim();
    setInput('');
    setIsStreaming(true);
    setCurrentTool(null);

    // Auto-create chat if none
    let chatId = activeChatId;
    if (!chatId) {
      try {
        const r = await fetch(`${AGENT_API}/chats`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
        const chat = await r.json();
        chatId = chat.id;
        setActiveChatId(chatId);
        setChatList(prev => [{ id: chat.id, title: chat.title, messageCount: 0, lastActivity: chat.lastActivity }, ...prev]);
      } catch {}
    }

    const newMessages = [...messages, { role: 'user', content: userMessage }];
    setMessages(newMessages);
    let currentIdx = newMessages.length;
    setMessages(prev => [...prev, { role: 'assistant', content: '', status: 'streaming' }]);

    try {
      const response = await fetch(`${AGENT_API}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: userMessage,
          chatId,
          context: {
            currentRoute: window.location.pathname,
            conversationHistory: newMessages.slice(-10).map(m => ({ role: m.role, content: m.content })),
          },
        }),
      });

      const reader = response.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop()!;

        for (const line of lines) {
          if (!line.startsWith('data: ')) continue;
          try {
            const event = JSON.parse(line.slice(6));
            if (event.type === 'session_created') {
              setSession({ active: true, branch: event.branch });
            } else if (event.type === 'new_block') {
              const role = event.blockType === 'tool' ? 'tool' : 'assistant';
              const content = event.blockType === 'tool' ? (event.label || '') : '';
              setMessages(prev => {
                const cleaned = prev.filter((m, i) => i < currentIdx || m.content.trim());
                currentIdx = cleaned.length;
                return [...cleaned, { role, content, status: 'streaming' }];
              });
            } else if (event.type === 'text') {
              setMessages(prev => {
                const updated = [...prev];
                if (updated[currentIdx]) updated[currentIdx] = { ...updated[currentIdx], content: updated[currentIdx].content + event.content };
                return updated;
              });
            } else if (event.type === 'tool_use') {
              setCurrentTool(event.label || event.tool);
            } else if (event.type === 'status') {
              setCurrentTool(event.detail ? `${event.content} ${event.detail}` : event.content);
            } else if (event.type === 'done') {
              setMessages(prev => {
                const updated = [...prev];
                if (updated[currentIdx]) updated[currentIdx] = { ...updated[currentIdx], status: 'done', commitHash: event.commitHash };
                return updated;
              });
              setCurrentTool(null);
              fetchChanges();
              fetchSession();
              fetchChatList(); // refresh titles
            } else if (event.type === 'error') {
              setMessages(prev => {
                const updated = [...prev];
                if (updated[currentIdx]) updated[currentIdx] = { ...updated[currentIdx], content: updated[currentIdx].content + '\n\nError: ' + event.content, status: 'error' };
                return updated;
              });
            }
          } catch { /* skip */ }
        }
      }
    } catch (err: any) {
      setMessages(prev => {
        const updated = [...prev];
        if (updated[currentIdx]) updated[currentIdx] = { role: 'assistant', content: 'Connection error: ' + err.message, status: 'error' };
        return updated;
      });
    }
    setIsStreaming(false);
    setCurrentTool(null);
  }, [input, isStreaming, messages, activeChatId, fetchChanges, fetchSession, fetchChatList]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
  };

  // --- Git actions ---
  const handleAccept = async () => {
    const label = prompt('Version label:');
    if (!label) return;
    const r = await fetch(`${AGENT_API}/session/accept`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ label }) });
    const d = await r.json();
    if (d.success) { setSession({ active: false, branch: null }); setChanges([]); setMainTab('versions'); }
    else alert('Failed: ' + d.error);
  };

  const handleDiscard = async () => {
    if (!confirm('Discard all changes?')) return;
    const r = await fetch(`${AGENT_API}/session/discard`, { method: 'POST' });
    const d = await r.json();
    if (d.success) { setSession({ active: false, branch: null }); setChanges([]); setTimeout(() => window.location.reload(), 1000); }
    else alert('Failed: ' + d.error);
  };

  const handleSessionRollback = async (hash: string) => {
    if (!confirm(`Reset to ${hash}?`)) return;
    await fetch(`${AGENT_API}/session/rollback`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ commitHash: hash }) });
    fetchChanges();
    setTimeout(() => window.location.reload(), 1000);
  };

  const changeCount = changes.length;

  // --- FAB ---
  if (!isOpen) {
    return (
      <button className="ccrs-chat-fab" onClick={() => setIsOpen(true)} title="AI Editor">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
        {session.active && <span className="ccrs-session-dot" />}
        {changeCount > 0 && <span className="ccrs-badge">{changeCount}</span>}
      </button>
    );
  }

  // --- Panel ---
  return (
    <div className="ccrs-chat-panel">
      {/* Header tabs */}
      <div className="ccrs-chat-header">
        <div className="ccrs-chat-tabs">
          <button className={`ccrs-tab ${mainTab === 'chat' ? 'active' : ''}`} onClick={() => setMainTab('chat')}>
            Chat {session.active && <span className="ccrs-session-indicator" />}
          </button>
          <button className={`ccrs-tab ${mainTab === 'changes' ? 'active' : ''}`} onClick={() => { setMainTab('changes'); fetchChanges(); }}>
            Changes{changeCount > 0 && <span className="ccrs-tab-count">{changeCount}</span>}
          </button>
          <button className={`ccrs-tab ${mainTab === 'versions' ? 'active' : ''}`} onClick={() => setMainTab('versions')}>Versions</button>
        </div>
        <button className="ccrs-chat-close" onClick={() => setIsOpen(false)}>&times;</button>
      </div>

      {mainTab === 'versions' ? (
        <VersionPanel sessionActive={session.active} />
      ) : mainTab === 'changes' ? (
        <div className="ccrs-staged">
          {session.active && (
            <div className="ccrs-staged-actions">
              <button className="ccrs-btn-save-staged" onClick={handleAccept} disabled={changeCount === 0 || isStreaming}>Accept Changes</button>
              <button className="ccrs-btn-discard" onClick={handleDiscard} disabled={isStreaming}>Discard All</button>
            </div>
          )}
          {!session.active ? (
            <div className="ccrs-staged-empty">No active session. Send a chat message to start editing.</div>
          ) : changeCount === 0 ? (
            <div className="ccrs-staged-empty">Session active but no changes yet.</div>
          ) : (
            <div className="ccrs-staged-list">
              {changes.map((c) => (
                <div key={c.hash} className="ccrs-staged-item">
                  <div className="ccrs-staged-desc">{c.message.replace(/^AI:\s*/, '')}</div>
                  <div className="ccrs-staged-meta">
                    <code>{c.hash}</code>
                    <span>{new Date(c.date).toLocaleTimeString()}</span>
                    <button className="ccrs-btn-rollback-sm" onClick={() => handleSessionRollback(c.hash)} disabled={isStreaming}>reset here</button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      ) : (
        <>
          {/* Chat session tabs */}
          <div className="ccrs-chat-session-tabs">
            {chatList.map((chat) => (
              <button
                key={chat.id}
                className={`ccrs-session-tab ${chat.id === activeChatId ? 'active' : ''}`}
                onClick={() => loadChat(chat.id)}
                title={chat.title}
              >
                <span className="ccrs-session-tab-title">{chat.title}</span>
                <span className="ccrs-session-tab-close" onClick={(e) => closeChat(chat.id, e)}>&times;</span>
              </button>
            ))}
            <button className="ccrs-session-tab-add" onClick={createNewChat} title="New chat">+</button>
          </div>

          {/* Messages */}
          <div className="ccrs-chat-messages">
            {messages.length === 0 && (
              <div className="ccrs-chat-empty">
                Describe a UI change and I'll edit the code live.<br />
                <span className="ccrs-chat-hint">e.g. "add a status badge to the department list"</span>
              </div>
            )}
            {messages.map((msg, i) => (
              <div key={i} className={`ccrs-msg ccrs-msg-${msg.role}`}>
                <div className="ccrs-msg-content">
                  {msg.role === 'assistant' ? renderMarkdown(msg.content) : msg.role === 'user' ? msg.content.split('\n').map((line, j) => <span key={j}>{j > 0 && <br />}{line}</span>) : msg.content}
                  {!msg.content && msg.status === 'streaming' && <span className="ccrs-typing">...</span>}
                </div>
                {msg.commitHash && <div className="ccrs-msg-commit">Changes applied [{msg.commitHash}]</div>}
              </div>
            ))}
            <div ref={messagesEndRef} />
          </div>

          {(isStreaming || currentTool) && (
            <div className="ccrs-chat-status">
              <span className="ccrs-spinner" />
              <span className="ccrs-status-text">{currentTool || 'Thinking...'}</span>
            </div>
          )}

          <div className="ccrs-chat-input-area">
            <textarea ref={inputRef} className="ccrs-chat-input" value={input} onChange={e => setInput(e.target.value)} onKeyDown={handleKeyDown} placeholder="Describe a UI change..." rows={2} disabled={isStreaming} />
            <button className="ccrs-chat-send" onClick={sendMessage} disabled={isStreaming || !input.trim()}>Send</button>
          </div>
        </>
      )}
    </div>
  );
}
