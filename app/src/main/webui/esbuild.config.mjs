import { build, context } from "esbuild";
import { existsSync, statSync, readdirSync, readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PKGS = resolve(__dirname, ".casehub-packages/packages");

const isWatch = process.argv.includes("--watch");

const pkgMap = new Map();
if (existsSync(PKGS)) {
  for (const dir of readdirSync(PKGS)) {
    const pkgJsonPath = resolve(PKGS, dir, "package.json");
    if (existsSync(pkgJsonPath)) {
      try {
        const raw = JSON.parse(readFileSync(pkgJsonPath, "utf8"));
        if (raw.name) pkgMap.set(raw.name, resolve(PKGS, dir));
      } catch {}
    }
  }
}

function tryResolve(basePath) {
  if (existsSync(basePath) && statSync(basePath).isFile()) return basePath;
  if (existsSync(basePath) && statSync(basePath).isDirectory()) {
    const idx = resolve(basePath, "index.js");
    if (existsSync(idx)) return idx;
  }
  const withJs = basePath + ".js";
  if (existsSync(withJs)) return withJs;
  return null;
}

const casehubResolvePlugin = {
  name: "casehub-resolve",
  setup(b) {
    b.onResolve({ filter: /^@casehubio\// }, (args) => {
      const match = args.path.match(/^(@casehubio\/[^/]+)(?:\/(.+))?$/);
      if (!match) return null;
      const [, pkgName, subpath] = match;
      const pkgDir = pkgMap.get(pkgName);
      if (!pkgDir) return null;
      if (!subpath) {
        const distIdx = resolve(pkgDir, "dist", "index.js");
        if (existsSync(distIdx)) return { path: distIdx };
        const srcIdx = resolve(pkgDir, "src", "index.ts");
        if (existsSync(srcIdx)) return { path: srcIdx };
        return null;
      }
      const distPath = resolve(pkgDir, "dist", subpath);
      const resolved = tryResolve(distPath);
      if (resolved) return { path: resolved };
      const srcPath = resolve(pkgDir, "src", subpath);
      const resolvedSrc = tryResolve(srcPath);
      if (resolvedSrc) return { path: resolvedSrc };
      return null;
    });
  },
};

const options = {
  entryPoints: ["src/app.ts", "src/terminal.ts"],
  bundle: true,
  outdir: "dist",
  format: "esm",
  splitting: true,
  target: "es2020",
  minify: !isWatch,
  sourcemap: isWatch,
  plugins: [casehubResolvePlugin],
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}
