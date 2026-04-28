import { motion } from "framer-motion";

export default function StreamingCursor() {
  return (
    <span className="sc-wrap">
      <motion.span
        className="sc-dot"
        animate={{ opacity: [0.2, 1, 0.2], scale: [0.8, 1.2, 0.8] }}
        transition={{ repeat: Infinity, duration: 1, ease: "easeInOut" }}
      />
    </span>
  );
}
