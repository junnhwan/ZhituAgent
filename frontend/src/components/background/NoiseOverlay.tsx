export default function NoiseOverlay() {
  return (
    <div className="noise-bg" aria-hidden="true">
      <svg width="100%" height="100%">
        <filter id="noise">
          <feTurbulence type="fractalNoise" baseFrequency="0.65" numOctaves={3} stitchTiles="stitch" />
        </filter>
        <rect width="100%" height="100%" filter="url(#noise)" />
      </svg>
    </div>
  );
}

export function NoiseOverlayStyles() {
  return (
    <style>{`
      .noise-bg {
        position: fixed; inset: 0; z-index: -1; opacity: 0.035; pointer-events: none;
      }
    `}</style>
  );
}
