FROM node:20-alpine
WORKDIR /app
COPY src ./src
COPY test ./test
ENV GAP_LOG=/gaps/gaps.jsonl
EXPOSE 9100
CMD ["node", "src/server.js"]
