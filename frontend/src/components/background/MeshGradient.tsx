export default function MeshGradient() {
  return <div className="mesh-bg" />;
}

export function MeshGradientStyles() {
  return (
    <style>{`
      .mesh-bg {
        position: fixed; inset: 0; z-index: -2; pointer-events: none;
        background:
          radial-gradient(ellipse 80% 50% at 15% 5%,  rgba(56,189,248,0.14) 0%, transparent 55%),
          radial-gradient(ellipse 50% 70% at 85% 10%, rgba(245,158,11,0.09) 0%, transparent 50%),
          radial-gradient(ellipse 60% 60% at 90% 90%, rgba(56,189,248,0.10) 0%, transparent 55%),
          radial-gradient(ellipse 50% 50% at 5%  85%, rgba(168,85,247,0.07) 0%, transparent 50%),
          linear-gradient(175deg, #fdfbf7 0%, #f8f4ee 50%, #f0ebe3 100%);
      }
    `}</style>
  );
}
