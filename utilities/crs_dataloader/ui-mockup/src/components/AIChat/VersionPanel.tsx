import { useState, useEffect, useCallback } from 'react';

const AGENT_API = '/api/agent';

interface Version {
  hash: string;
  fullHash: string;
  message: string;
  date: string;
  author: string;
  label: string | null;
  isMerge?: boolean;
}

export default function VersionPanel({ sessionActive }: { sessionActive: boolean }) {
  const [versions, setVersions] = useState<Version[]>([]);
  const [loading, setLoading] = useState(true);
  const [rollingBack, setRollingBack] = useState<string | null>(null);

  const fetchVersions = useCallback(async () => {
    try {
      const res = await fetch(`${AGENT_API}/versions`);
      setVersions(await res.json());
    } catch (err) { console.error('Failed to fetch versions:', err); }
    setLoading(false);
  }, []);

  useEffect(() => { fetchVersions(); }, [fetchVersions]);

  const handleRollback = async (commitHash: string) => {
    if (!window.confirm(`Rollback to ${commitHash}? This resets the main branch.`)) return;
    setRollingBack(commitHash);
    try {
      const res = await fetch(`${AGENT_API}/versions/rollback`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ commitHash }),
      });
      const data = await res.json();
      if (data.success) setTimeout(() => window.location.reload(), 1500);
      else { alert('Failed: ' + data.error); setRollingBack(null); }
    } catch (err: any) { alert('Error: ' + err.message); setRollingBack(null); }
  };

  if (loading) return <div className="ccrs-versions-loading">Loading versions...</div>;

  return (
    <div className="ccrs-versions">
      {sessionActive && (
        <div className="ccrs-versions-notice">
          Session active — accept or discard changes before rolling back versions.
        </div>
      )}
      <div className="ccrs-versions-list">
        {versions.length === 0 && <div className="ccrs-versions-empty">No versions yet. Accept a session to create one.</div>}
        {versions.map((v, i) => (
          <div key={v.hash} className={`ccrs-version-item ${i === 0 ? 'ccrs-version-current' : ''}`}>
            <div className="ccrs-version-top">
              <code className="ccrs-version-hash">{v.hash}</code>
              {i === 0 && <span className="ccrs-version-current-badge">current</span>}
              {v.label && v.label !== v.message && <span className="ccrs-version-label">{v.label}</span>}
              <span className="ccrs-version-date">{new Date(v.date).toLocaleString()}</span>
            </div>
            <div className="ccrs-version-msg">{v.label || v.message}</div>
            {i > 0 && (
              <button
                className="ccrs-btn-rollback"
                onClick={() => handleRollback(v.hash)}
                disabled={rollingBack !== null || sessionActive}
              >
                {rollingBack === v.hash ? 'Rolling back...' : 'Rollback'}
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
