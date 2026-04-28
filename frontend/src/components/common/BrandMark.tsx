export default function BrandMark() {
  return (
    <div className="brand-mark">
      <div className="brand-mark-inner" />
    </div>
  );
}

export function BrandMarkStyles() {
  return (
    <style>{`
      .brand-mark {
        width: 40px;
        height: 40px;
        border-radius: 12px;
        background: var(--gradient-azure);
        display: flex;
        align-items: center;
        justify-content: center;
        box-shadow: 0 4px 16px rgba(56, 189, 248, 0.3);
        position: relative;
      }

      .brand-mark::before {
        content: '';
        position: absolute;
        inset: 0;
        border-radius: 12px;
        background: linear-gradient(135deg, rgba(255, 255, 255, 0.3) 0%, transparent 50%);
      }

      .brand-mark-inner {
        width: 14px;
        height: 14px;
        border-radius: 50%;
        background: rgba(255, 255, 255, 0.9);
        box-shadow: 0 0 8px rgba(255, 255, 255, 0.5);
      }
    `}</style>
  );
}
