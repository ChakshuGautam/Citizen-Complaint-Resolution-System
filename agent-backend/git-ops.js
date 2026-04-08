const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = "/opt/egov/ccrs-dashboard";
const MAIN_BRANCH = "feat/ai-ui-editor";
const SESSION_PREFIX = "ai/session-";
const ACTIVE_SESSION_FILE = path.join(__dirname, ".active-session");

function exec(cmd) {
  return execSync(cmd, { cwd: REPO_ROOT, encoding: "utf-8" }).trim();
}

// --- Branch helpers ---

function getCurrentBranch() {
  return exec("git rev-parse --abbrev-ref HEAD");
}

function isOnMain() {
  return getCurrentBranch() === MAIN_BRANCH;
}

// --- Session management ---

function getSession() {
  try {
    if (fs.existsSync(ACTIVE_SESSION_FILE)) {
      const branch = fs.readFileSync(ACTIVE_SESSION_FILE, "utf-8").trim();
      if (branch) {
        // Verify branch actually exists
        try {
          exec(`git rev-parse --verify ${branch}`);
          return branch;
        } catch {
          // Branch gone, clean up stale file
          fs.unlinkSync(ACTIVE_SESSION_FILE);
        }
      }
    }
  } catch {}
  return null;
}

function getSessionStatus() {
  const session = getSession();
  return {
    active: session !== null,
    branch: session,
    onMain: isOnMain(),
    currentBranch: getCurrentBranch(),
  };
}

function createSession() {
  if (getSession()) {
    // Already have a session — just return it
    return { branch: getSession(), created: false };
  }
  // Make sure we're on main
  if (!isOnMain()) {
    exec(`git checkout ${MAIN_BRANCH}`);
  }
  const branch = `${SESSION_PREFIX}${Date.now()}`;
  exec(`git checkout -b ${branch}`);
  fs.writeFileSync(ACTIVE_SESSION_FILE, branch, "utf-8");
  return { branch, created: true };
}

function autoCommit(message) {
  try {
    exec("git add -A");
    const status = exec("git status --porcelain");
    if (!status) return null;
    const safe = message.replace(/"/g, '\\"').replace(/\n/g, " ");
    exec(`git commit -m "${safe}"`);
    return exec("git rev-parse --short HEAD");
  } catch (e) {
    console.error("autoCommit failed:", e.message);
    return null;
  }
}

function getSessionChanges() {
  const session = getSession();
  if (!session) return [];
  try {
    const raw = exec(
      `git log ${MAIN_BRANCH}..HEAD --format='%h||%s||%ci' 2>/dev/null`
    );
    if (!raw) return [];
    return raw
      .split("\n")
      .filter(Boolean)
      .map((line) => {
        const [hash, message, date] = line.split("||");
        return { hash, message, date };
      });
  } catch {
    return [];
  }
}

function resetSession(commitHash) {
  const session = getSession();
  if (!session) return { success: false, error: "No active session" };
  try {
    exec(`git reset --hard ${commitHash}`);
    return { success: true, hash: exec("git rev-parse --short HEAD") };
  } catch (e) {
    return { success: false, error: e.message };
  }
}

function acceptSession(label) {
  const session = getSession();
  if (!session) return { success: false, error: "No active session" };
  try {
    const safe = label.replace(/"/g, '\\"');
    exec(`git checkout ${MAIN_BRANCH}`);
    exec(`git merge --no-ff ${session} -m "Accept: ${safe}"`);
    exec(`git branch -d ${session}`);
    if (fs.existsSync(ACTIVE_SESSION_FILE)) fs.unlinkSync(ACTIVE_SESSION_FILE);
    return { success: true, hash: exec("git rev-parse --short HEAD") };
  } catch (e) {
    // Try to recover — go back to session if merge failed
    try { exec(`git checkout ${session}`); } catch {}
    return { success: false, error: e.message };
  }
}

function discardSession() {
  const session = getSession();
  if (!session) return { success: false, error: "No active session" };
  try {
    exec(`git checkout ${MAIN_BRANCH}`);
    exec(`git branch -D ${session}`);
    if (fs.existsSync(ACTIVE_SESSION_FILE)) fs.unlinkSync(ACTIVE_SESSION_FILE);
    return { success: true };
  } catch (e) {
    return { success: false, error: e.message };
  }
}

// --- Versions (merge commits on main) ---

function getVersions(limit = 30) {
  try {
    // Only show our "Accept:" merge commits, not upstream merges
    const raw = exec(
      `git log ${MAIN_BRANCH} --merges --first-parent --grep="^Accept:" --format='%h||%H||%s||%ci||%an' -n ${limit} 2>/dev/null`
    );
    if (!raw) return [];
    return raw
      .split("\n")
      .filter(Boolean)
      .map((line) => {
        const [hash, fullHash, message, date, author] = line.split("||");
        const label = message.startsWith("Accept: ") ? message.slice(8) : message;
        return { hash, fullHash, message, date, author, label };
      });
  } catch {
    return [];
  }
}

function rollbackMain(commitHash) {
  if (getSession()) {
    return { success: false, error: "Cannot rollback main while session is active" };
  }
  if (!isOnMain()) {
    try { exec(`git checkout ${MAIN_BRANCH}`); } catch {}
  }
  try {
    exec(`git reset --hard ${commitHash}`);
    return { success: true, hash: exec("git rev-parse --short HEAD") };
  } catch (e) {
    return { success: false, error: e.message };
  }
}

// --- Cleanup on startup ---

function cleanupOrphans() {
  try {
    const raw = exec(`git branch --list '${SESSION_PREFIX}*'`);
    if (!raw) return 0;
    const branches = raw
      .split("\n")
      .map((b) => b.trim().replace("* ", ""))
      .filter(Boolean);

    // Switch to main first
    if (!isOnMain()) {
      exec(`git checkout ${MAIN_BRANCH}`);
    }
    let cleaned = 0;
    for (const branch of branches) {
      try {
        exec(`git branch -D ${branch}`);
        cleaned++;
      } catch {}
    }
    if (fs.existsSync(ACTIVE_SESSION_FILE)) fs.unlinkSync(ACTIVE_SESSION_FILE);
    console.log(`Cleaned ${cleaned} orphan session branches`);
    return cleaned;
  } catch {
    return 0;
  }
}

// --- Compat: get full log (for versions tab fallback) ---

function getLog(limit = 30) {
  try {
    const raw = exec(
      `git log ${MAIN_BRANCH} --first-parent --format='%h||%H||%s||%ci||%an' -n ${limit}`
    );
    return raw
      .split("\n")
      .filter(Boolean)
      .map((line) => {
        const [hash, fullHash, message, date, author] = line.split("||");
        const isMerge = message.startsWith("Accept: ");
        return { hash, fullHash, message, date, author, label: isMerge ? message.slice(8) : null, isMerge };
      });
  } catch {
    return [];
  }
}

module.exports = {
  MAIN_BRANCH,
  getCurrentBranch,
  isOnMain,
  getSession,
  getSessionStatus,
  createSession,
  autoCommit,
  getSessionChanges,
  resetSession,
  acceptSession,
  discardSession,
  getVersions,
  rollbackMain,
  cleanupOrphans,
  getLog,
};
