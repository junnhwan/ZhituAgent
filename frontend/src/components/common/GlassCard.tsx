import type { ReactNode } from "react";
import "./GlassCard.css";

interface GlassCardProps {
  children: ReactNode;
  className?: string;
  style?: React.CSSProperties;
}

export default function GlassCard({ children, className = "", style }: GlassCardProps) {
  return (
    <div className={`premium-card ${className}`} style={style}>
      {children}
    </div>
  );
}
