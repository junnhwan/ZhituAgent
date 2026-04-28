interface PanelLabelProps {
  children: React.ReactNode;
}

export default function PanelLabel({ children }: PanelLabelProps) {
  return <span className="panel-label">{children}</span>;
}

export function PanelLabelStyles() {
  return (
    <style>{`
      .panel-label {
        color: var(--text-ghost);
        font-size: 0.72rem;
        font-weight: 800;
        letter-spacing: 0.12em;
        text-transform: uppercase;
      }
    `}</style>
  );
}
