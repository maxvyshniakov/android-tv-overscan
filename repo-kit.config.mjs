export default {
  verify: {
    slots: 1,
    steps: [
      { name: "launcher tests", command: ["run", "test:launcher"], memory: 0.3 },
      { name: "launcher cli", command: ["run", "check:cli"], memory: 0.2 },
      { name: "java tests", command: ["run", "test:java"], memory: 0.5 },
      { name: "android apk", command: ["run", "build:apk"], memory: 1.0 },
      { name: "kit", command: ["run", "check:kit"], memory: 0.1 },
    ],
  },
  prePush: { slots: 1 },
};
