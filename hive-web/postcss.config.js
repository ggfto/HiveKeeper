export default {
  plugins: {
    // Tailwind v4 ships its own PostCSS plugin (with vendor prefixing built in via
    // Lightning CSS), so autoprefixer is no longer needed in the chain.
    '@tailwindcss/postcss': {},
  },
}
