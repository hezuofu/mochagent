import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [
    react(),
    {
      name: 'html-spa-fallback',
      configureServer(server) {
        server.middlewares.use((req, _res, next) => {
          // .html 路径转发到 index.html 以支持 SPA 路由刷新
          const url = (req as any).url as string | undefined;
          if (url && url.endsWith('.html') && !url.startsWith('/api')) {
            (req as any).url = '/';
          }
          next();
        });
      },
    },
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
