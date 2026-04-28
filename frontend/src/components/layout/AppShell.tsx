import type { ReactNode } from "react";
import "./AppShell.css";

export default function AppShell({
  sidebar,
  main,
  aside,
  composer,
}: {
  sidebar: ReactNode;
  main: ReactNode;
  aside: ReactNode;
  composer: ReactNode;
}) {
  return (
    <div className="shell">
      <div className="shell-nav">{sidebar}</div>
      <div className="shell-center">
        {main}
        {composer}
      </div>
      <div className="shell-aside">{aside}</div>
    </div>
  );
}
