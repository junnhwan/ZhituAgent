import { createContext, useContext, useState, useEffect } from 'react';
import type { ReactNode } from 'react';
import { authApi } from '../api/auth';

interface User {
  id: string;
  email: string;
  tenantId: string;
}

interface AuthContextType {
  user: User | null;
  token: string | null;
  login: (tenantId: string, email: string, password: string) => Promise<void>;
  register: (tenantId: string, email: string, password: string) => Promise<void>;
  logout: () => void;
  isAuthenticated: boolean;
}

const AuthContext = createContext<AuthContextType | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(localStorage.getItem('zhitu_token'));

  useEffect(() => {
    if (token) {
      localStorage.setItem('zhitu_token', token);
    } else {
      localStorage.removeItem('zhitu_token');
    }
  }, [token]);

  const login = async (tenantId: string, email: string, password: string) => {
    const response = await authApi.login({ tenantId, email, password });
    setToken(response.token);
    setUser(response.user);
  };

  const register = async (tenantId: string, email: string, password: string) => {
    const response = await authApi.register({ tenantId, email, password });
    setToken(response.token);
    setUser(response.user);
  };

  const logout = () => {
    setToken(null);
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{
      user,
      token,
      login,
      register,
      logout,
      isAuthenticated: !!token
    }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
}
