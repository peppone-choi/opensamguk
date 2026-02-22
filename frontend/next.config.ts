import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "storage.hided.net",
        pathname: "/gitea/devsam/image/raw/branch/main/**",
      },
    ],
  },
};

export default nextConfig;
