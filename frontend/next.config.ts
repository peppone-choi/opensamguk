import type { NextConfig } from "next";
import type { RemotePattern } from "next/dist/shared/lib/image-config";

const defaultImageCdn =
  "https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-image@master/";

function toRemotePattern(url: string): RemotePattern {
  const parsed = new URL(url);
  const pathname = parsed.pathname.endsWith("/")
    ? `${parsed.pathname}**`
    : `${parsed.pathname}/**`;

  return {
    protocol: parsed.protocol.replace(":", "") as "http" | "https",
    hostname: parsed.hostname,
    pathname,
  };
}

const imageCdnBase = process.env.NEXT_PUBLIC_IMAGE_CDN_BASE ?? defaultImageCdn;

const nextConfig: NextConfig = {
  output: "standalone",
  images: {
    remotePatterns: [toRemotePattern(imageCdnBase)],
  },
};

export default nextConfig;
