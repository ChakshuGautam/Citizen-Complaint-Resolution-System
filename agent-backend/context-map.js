/**
 * Static mapping of routes to the PGR component files that render them.
 * Used to give Claude targeted context about which files to edit.
 */

const PGR_SRC = "frontend/micro-ui/web/micro-ui-internals/packages/modules/pgr/src";
const CFG_SRC = "utilities/crs_dataloader/ui-mockup/src";

const ROUTE_COMPONENTS = {
  // === Configurator routes ===
  "/login": {
    description: "Configurator login page",
    files: [CFG_SRC + "/pages/LoginPage.tsx", CFG_SRC + "/api/client.ts", CFG_SRC + "/api/config.ts"],
  },
  "/manage": {
    description: "Configurator management dashboard (react-admin)",
    files: [CFG_SRC + "/admin/DigitDashboard.tsx", CFG_SRC + "/admin/DigitLayout.tsx", CFG_SRC + "/App.tsx"],
  },
  "/manage/departments": {
    description: "Departments CRUD list",
    files: [CFG_SRC + "/resources/departments/index.tsx", CFG_SRC + "/admin/DigitList.tsx", CFG_SRC + "/admin/DigitDatagrid.tsx"],
  },
  "/manage/employees": {
    description: "Employees CRUD list",
    files: [CFG_SRC + "/resources/employees/index.tsx", CFG_SRC + "/admin/DigitList.tsx", CFG_SRC + "/admin/DigitDatagrid.tsx"],
  },
  "/manage/complaints": {
    description: "Complaints CRUD list",
    files: [CFG_SRC + "/resources/complaints/index.tsx", CFG_SRC + "/admin/DigitList.tsx", CFG_SRC + "/admin/DigitDatagrid.tsx"],
  },
  "/manage/complaint-types": {
    description: "Complaint types CRUD list",
    files: [CFG_SRC + "/resources/complaint-types/index.tsx", CFG_SRC + "/admin/DigitList.tsx"],
  },
  "/manage/boundaries": {
    description: "Boundaries CRUD list",
    files: [CFG_SRC + "/resources/boundaries/index.tsx", CFG_SRC + "/admin/DigitList.tsx"],
  },
  "/manage/localization": {
    description: "Localization strings CRUD",
    files: [CFG_SRC + "/resources/localization/index.tsx", CFG_SRC + "/admin/DigitList.tsx"],
  },
  "/manage/users": {
    description: "Users CRUD list",
    files: [CFG_SRC + "/resources/users/index.tsx", CFG_SRC + "/admin/DigitList.tsx"],
  },
  "/manage/charts": {
    description: "Analytics dashboard with Recharts — bar, line, pie, area charts showing DIGIT data",
    files: [CFG_SRC + "/pages/ChartsPage.tsx"],
  },
  "/manage/advanced": {
    description: "Advanced settings page",
    files: [CFG_SRC + "/resources/advanced/AdvancedPage.tsx"],
  },
  "/phase/1": {
    description: "Onboarding phase 1",
    files: [CFG_SRC + "/pages/Phase1Page.tsx"],
  },
  "/phase/2": {
    description: "Onboarding phase 2",
    files: [CFG_SRC + "/pages/Phase2Page.tsx"],
  },
  "/phase/3": {
    description: "Onboarding phase 3",
    files: [CFG_SRC + "/pages/Phase3Page.tsx"],
  },
  "/phase/4": {
    description: "Onboarding phase 4",
    files: [CFG_SRC + "/pages/Phase4Page.tsx"],
  },
  // === PGR micro-ui routes ===
  "/employee/pgr/inbox-v2": {
    description: "Employee complaint inbox - search, filter, and list complaints",
    files: [
      PGR_SRC + "/pages/employee/PGRInbox.js",
      PGR_SRC + "/configs/PGRSearchInboxConfig.js",
      PGR_SRC + "/configs/UICustomizations.js",
    ],
  },
  "/employee/pgr/create-complaint": {
    description: "Employee create complaint form",
    files: [
      PGR_SRC + "/pages/employee/CreateComplaint/index.js",
      PGR_SRC + "/configs/CreateComplaintConfig.js",
      PGR_SRC + "/components/BoundaryComponent.js",
      PGR_SRC + "/components/GeoLocations.js",
    ],
  },
  "/employee/pgr/complaint-details": {
    description: "Employee complaint detail view - timeline, assignment, workflow actions",
    files: [
      PGR_SRC + "/pages/employee/PGRDetails.js",
      PGR_SRC + "/components/TimeLine.js",
      PGR_SRC + "/components/TimeLineWrapper.js",
      PGR_SRC + "/components/Complaint.js",
      PGR_SRC + "/components/AssigneeComponent.js",
      PGR_SRC + "/components/PGRWorkflowModal.js",
      PGR_SRC + "/components/ComplaintPhotos.js",
    ],
  },
  "/citizen/pgr/complaints": {
    description: "Citizen complaint list",
    files: [
      PGR_SRC + "/pages/citizen/ComplaintsList.js",
    ],
  },
  "/citizen/pgr/create-complaint": {
    description: "Citizen create complaint flow - multi-step form",
    files: [
      PGR_SRC + "/pages/citizen/Create/FormExplorer.js",
    ],
  },
  "/citizen/pgr/complaint/details": {
    description: "Citizen complaint detail view",
    files: [
      PGR_SRC + "/pages/citizen/ComplaintDetails.js",
    ],
  },
};

// Configurator shared files
const CFG_SHARED = [
  CFG_SRC + "/App.tsx",
  CFG_SRC + "/admin/DigitList.tsx",
  CFG_SRC + "/admin/DigitDatagrid.tsx",
  CFG_SRC + "/admin/DigitLayout.tsx",
  CFG_SRC + "/admin/schemaUtils.ts",
  CFG_SRC + "/api/client.ts",
  CFG_SRC + "/providers/bridge.ts",
];

// PGR shared files
const SHARED_FILES = [
  PGR_SRC + "/Module.js",
  PGR_SRC + "/services/pgr/PGRService.js",
  PGR_SRC + "/utils/index.js",
  PGR_SRC + "/utils/constants.js",
  PGR_SRC + "/utils/urls.js",
  PGR_SRC + "/redux/actions/complaint.js",
  PGR_SRC + "/redux/reducers/complaintReducer.js",
];

function resolveContext(currentRoute) {
  // Find the best matching route
  let bestMatch = null;
  let bestLen = 0;
  for (const pattern of Object.keys(ROUTE_COMPONENTS)) {
    if (currentRoute.includes(pattern) && pattern.length > bestLen) {
      bestMatch = pattern;
      bestLen = pattern.length;
    }
  }

  const routeInfo = bestMatch ? ROUTE_COMPONENTS[bestMatch] : null;
  // Pick shared files based on whether route is configurator or PGR
  const isConfigurator = currentRoute.includes("/manage") || currentRoute.includes("/phase") || currentRoute.includes("/login");
  return {
    matchedRoute: bestMatch,
    description: routeInfo ? routeInfo.description : "Unknown page",
    relevantFiles: routeInfo ? routeInfo.files : [],
    sharedFiles: isConfigurator ? CFG_SHARED : SHARED_FILES,
  };
}

module.exports = { resolveContext, ROUTE_COMPONENTS, SHARED_FILES, PGR_SRC };
