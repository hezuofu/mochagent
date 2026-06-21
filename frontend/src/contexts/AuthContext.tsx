import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { authApi, LoginResult } from '../api/auth';

interface AuthState {
  token: string | null;
  user: LoginResult | null;
  isAuthenticated: boolean;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState>({} as AuthState);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('token'));
  const [user, setUser] = useState<LoginResult | null>(() => {
    const cached = localStorage.getItem('user');
    return cached ? JSON.parse(cached) : null;
  });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(false);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const result = await authApi.login({ username, password });
    localStorage.setItem('token', result.token);
    localStorage.setItem('user', JSON.stringify(result));
    setToken(result.token);
    setUser(result);
  }, []);

  const logout = useCallback(() => {
    authApi.logout().catch(() => {});
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setToken(null);
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider
      value={{
        token,
        user,
        isAuthenticated: !!token,
        loading,
        login,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
