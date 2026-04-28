import type { ButtonHTMLAttributes, ReactNode } from "react";
import { motion } from "framer-motion";
import "./Button.css";

interface ButtonProps {
  variant?: "primary" | "outline" | "ghost";
  icon?: ReactNode;
  children: ReactNode;
  className?: string;
  disabled?: boolean;
  onClick?: () => void;
  type?: ButtonHTMLAttributes<HTMLButtonElement>["type"];
}

export default function Button({
  variant = "primary",
  icon,
  children,
  className = "",
  disabled,
  onClick,
  type,
}: ButtonProps) {
  return (
    <motion.button
      type={type}
      className={`btn-premium btn-${variant} ${className}`}
      whileHover={disabled ? undefined : { y: -1 }}
      whileTap={disabled ? undefined : { scale: 0.98 }}
      disabled={disabled}
      onClick={onClick}
    >
      {icon}
      {children}
    </motion.button>
  );
}
