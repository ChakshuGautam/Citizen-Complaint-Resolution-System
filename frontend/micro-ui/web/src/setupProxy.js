const { createProxyMiddleware } = require("http-proxy-middleware");
const createProxy = createProxyMiddleware({
  target: process.env.REACT_APP_PROXY_URL,
  changeOrigin: true,
});
const agentProxy = createProxyMiddleware({
  target: "http://localhost:4100",
  changeOrigin: true,
});
module.exports = function (app) {
  // Agent backend proxy
  app.use("/api/agent", agentProxy);

  // DIGIT service proxies
  [
    "/egov-mdms-service",
    "/egov-location",
    "/localization",
    "/egov-workflow-v2",
    "/pgr-services",
    "/filestore",
    "/egov-hrms",
    "/user-otp",
    "/user",
    "/fsm",
    "/billing-service",
    "/collection-services",
    "/pdf-service",
    "/pg-service",
    "/vehicle",
    "/vendor",
    "/property-services",
    "/fsm-calculator/v1/billingSlab/_search",
    "/muster-roll",
    "/service-request",
    "/mdms-v2",
    "/access",
    "/egov-indexer",
    "/egov-enc-service",
    "/boundary-service",
    "/egov-url-shortening",
  ].forEach((location) =>
    app.use(location, createProxy)
  );
};
