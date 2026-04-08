import { useState, useEffect } from 'react';
import {
  BarChart, Bar, LineChart, Line, PieChart, Pie, Cell,
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
  ResponsiveContainer,
} from 'recharts';
import { DigitCard } from '@/components/digit';

// ── Types ──────────────────────────────────────────────────────────────────

/** Shape returned by /api/agent/pgr-stats */
interface StatsEntry {
  serviceCode: string;
  status: string;
  count: number;
  date: string;
}

interface StatsResponse {
  complaints: StatsEntry[];
  total: number;
}

interface StatusEntry  { name: string; value: number; color: string }
interface ServiceEntry { name: string; count: number }
interface TrendEntry   { name: string; complaints: number; resolved: number; _ts: number }

// ── Helpers ────────────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
  PENDINGATLME:          'hsl(var(--primary))',
  PENDINGFORASSIGNMENT:  'hsl(220, 70%, 55%)',
  RESOLVED:              'hsl(142, 60%, 45%)',
  CLOSEDAFTERRESOLUTION: 'hsl(160, 55%, 42%)',
  REJECTED:              'hsl(0, 70%, 55%)',
};
const FALLBACK_COLORS = [
  'hsl(var(--primary))',
  'hsl(220, 70%, 55%)',
  'hsl(142, 60%, 45%)',
  'hsl(0, 70%, 55%)',
  'hsl(45, 70%, 50%)',
  'hsl(280, 60%, 55%)',
];

/** Convert a camelCase / ALLCAPS service code into readable label */
function prettifyServiceCode(code: string): string {
  return code
    .replace(/([A-Z])/g, ' $1')
    .replace(/^[\s_]+/, '')
    .trim();
}

/**
 * Format a date string (YYYY-MM-DD, ISO, or epoch ms string) into "MMM YY"
 * and return an epoch ms value for chronological sorting.
 */
function parseDateEntry(date: string): { label: string; epoch: number } {
  const ms = isNaN(Number(date)) ? Date.parse(date) : Number(date);
  const d  = new Date(isNaN(ms) ? date : ms);
  const label = isNaN(d.getTime())
    ? date  // fall back to raw string if unparseable
    : d.toLocaleDateString('en-IN', { month: 'short', year: '2-digit' });
  const epoch = isNaN(d.getTime())
    ? 0
    : new Date(d.getFullYear(), d.getMonth(), 1).getTime();
  return { label, epoch };
}

/** Pie — group by status, sum counts */
function buildStatusData(entries: StatsEntry[]): StatusEntry[] {
  const counts: Record<string, number> = {};
  for (const e of entries) {
    counts[e.status] = (counts[e.status] || 0) + e.count;
  }
  return Object.entries(counts).map(([key, value], i) => ({
    name: key.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase()),
    value,
    color: STATUS_COLORS[key] ?? FALLBACK_COLORS[i % FALLBACK_COLORS.length],
  }));
}

/** Bar — group by serviceCode, sum counts, prettify labels */
function buildServiceData(entries: StatsEntry[]): ServiceEntry[] {
  const counts: Record<string, number> = {};
  for (const e of entries) {
    counts[e.serviceCode] = (counts[e.serviceCode] || 0) + e.count;
  }
  return Object.entries(counts)
    .map(([code, count]) => ({ name: prettifyServiceCode(code), count }))
    .sort((a, b) => b.count - a.count);
}

/** Line / Area — group by date, derive filed vs resolved totals */
function buildTrendData(entries: StatsEntry[]): TrendEntry[] {
  const byDate: Record<string, TrendEntry> = {};
  for (const e of entries) {
    const { label, epoch } = parseDateEntry(e.date);
    if (!byDate[label]) byDate[label] = { name: label, complaints: 0, resolved: 0, _ts: epoch };
    byDate[label].complaints += e.count;
    if (['RESOLVED', 'CLOSEDAFTERRESOLUTION'].includes(e.status)) {
      byDate[label].resolved += e.count;
    }
  }
  return Object.values(byDate).sort((a, b) => a._ts - b._ts);
}

// ── Tooltip style (shared) ─────────────────────────────────────────────────

const tooltipStyle = {
  background: 'hsl(var(--card))',
  border: '1px solid hsl(var(--border))',
  borderRadius: '6px',
  fontSize: '12px',
};

// ── Component ──────────────────────────────────────────────────────────────

export default function ChartsPage() {
  const [loading,     setLoading]     = useState(true);
  const [error,       setError]       = useState<string | null>(null);
  const [total,       setTotal]       = useState(0);
  const [statusData,  setStatusData]  = useState<StatusEntry[]>([]);
  const [serviceData, setServiceData] = useState<ServiceEntry[]>([]);
  const [trendData,   setTrendData]   = useState<TrendEntry[]>([]);

  useEffect(() => {
    fetch('/api/agent/pgr-stats')
      .then(r => {
        if (!r.ok) throw new Error(`pgr-stats failed: HTTP ${r.status}`);
        return r.json();
      })
      .then((data: StatsResponse) => {
        const entries = data.complaints ?? [];
        setTotal(data.total ?? entries.reduce((s, e) => s + e.count, 0));
        setStatusData(buildStatusData(entries));
        setServiceData(buildServiceData(entries));
        setTrendData(buildTrendData(entries));
        setLoading(false);
      })
      .catch((err: Error) => {
        setError(err.message);
        setLoading(false);
      });
  }, []);

  // ── Loading / Error states ───────────────────────────────────────────────

  if (loading) {
    return (
      <div className="p-6">
        <p className="text-muted-foreground animate-pulse">Fetching PGR complaints…</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-6">
        <p className="text-destructive font-medium">Failed to load PGR data</p>
        <p className="text-sm text-muted-foreground mt-1">{error}</p>
      </div>
    );
  }

  // ── Charts ───────────────────────────────────────────────────────────────

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold font-condensed text-foreground">
          Analytics Dashboard
        </h1>
        <p className="text-muted-foreground mt-1">
          Live PGR data — <span className="font-medium text-foreground">{total}</span> complaint{total !== 1 ? 's' : ''} via <span className="font-medium text-foreground">/api/agent/pgr-stats</span>
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">

        {/* Bar Chart — complaints by service type */}
        <DigitCard>
          <h3 className="text-sm font-semibold text-muted-foreground mb-4">Complaints by Service Type</h3>
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={serviceData} margin={{ top: 4, right: 8, left: 0, bottom: 40 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
              <XAxis
                dataKey="name"
                tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
                stroke="hsl(var(--muted-foreground))"
                angle={-30}
                textAnchor="end"
                interval={0}
              />
              <YAxis
                allowDecimals={false}
                tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }}
                stroke="hsl(var(--muted-foreground))"
              />
              <Tooltip contentStyle={tooltipStyle} />
              <Bar dataKey="count" fill="hsl(var(--primary))" radius={[4, 4, 0, 0]} name="Complaints" />
            </BarChart>
          </ResponsiveContainer>
        </DigitCard>

        {/* Pie Chart — status distribution */}
        <DigitCard>
          <h3 className="text-sm font-semibold text-muted-foreground mb-4">Complaint Status Distribution</h3>
          <ResponsiveContainer width="100%" height={280}>
            <PieChart>
              <Pie
                data={statusData}
                cx="50%"
                cy="50%"
                innerRadius={55}
                outerRadius={95}
                paddingAngle={3}
                dataKey="value"
                label={({ name, percent }) =>
                  percent > 0.05 ? `${name} ${(percent * 100).toFixed(0)}%` : ''
                }
                labelLine={false}
              >
                {statusData.map((entry, index) => (
                  <Cell key={index} fill={entry.color} />
                ))}
              </Pie>
              <Tooltip contentStyle={tooltipStyle} />
              <Legend wrapperStyle={{ fontSize: '12px' }} />
            </PieChart>
          </ResponsiveContainer>
        </DigitCard>

        {/* Line Chart — complaint vs resolved trend by month */}
        <DigitCard>
          <h3 className="text-sm font-semibold text-muted-foreground mb-4">Resolution Trend by Month</h3>
          <ResponsiveContainer width="100%" height={280}>
            <LineChart data={trendData}>
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
              <XAxis
                dataKey="name"
                tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }}
                stroke="hsl(var(--muted-foreground))"
              />
              <YAxis
                allowDecimals={false}
                tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }}
                stroke="hsl(var(--muted-foreground))"
              />
              <Tooltip contentStyle={tooltipStyle} />
              <Legend wrapperStyle={{ fontSize: '12px' }} />
              <Line
                type="monotone"
                dataKey="complaints"
                stroke="hsl(var(--primary))"
                strokeWidth={2}
                dot={{ r: 4 }}
                name="Filed"
              />
              <Line
                type="monotone"
                dataKey="resolved"
                stroke="hsl(142, 60%, 45%)"
                strokeWidth={2}
                dot={{ r: 4 }}
                name="Resolved"
              />
            </LineChart>
          </ResponsiveContainer>
        </DigitCard>

        {/* Area Chart — complaint volume over time */}
        <DigitCard>
          <h3 className="text-sm font-semibold text-muted-foreground mb-4">Complaint Volume Over Time</h3>
          <ResponsiveContainer width="100%" height={280}>
            <AreaChart data={trendData}>
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
              <XAxis
                dataKey="name"
                tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }}
                stroke="hsl(var(--muted-foreground))"
              />
              <YAxis
                allowDecimals={false}
                tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }}
                stroke="hsl(var(--muted-foreground))"
              />
              <Tooltip contentStyle={tooltipStyle} />
              <Legend wrapperStyle={{ fontSize: '12px' }} />
              <Area
                type="monotone"
                dataKey="complaints"
                stroke="hsl(var(--primary))"
                fill="hsl(var(--primary) / 0.15)"
                strokeWidth={2}
                name="Complaints"
              />
              <Area
                type="monotone"
                dataKey="resolved"
                stroke="hsl(142, 60%, 45%)"
                fill="hsl(142, 60%, 45% / 0.12)"
                strokeWidth={2}
                name="Resolved"
              />
            </AreaChart>
          </ResponsiveContainer>
        </DigitCard>

      </div>
    </div>
  );
}
