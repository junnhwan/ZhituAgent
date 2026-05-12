import { request } from './client';

interface AuthResponse {
  token: string;
  user: {
    id: string;
    email: string;
    tenantId: string;
  };
}

interface LoginRequest {
  tenantId: string;
  email: string;
  password: string;
}

export const authApi = {
  login: async (req: LoginRequest): Promise<AuthResponse> => {
    return request<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  },

  register: async (req: LoginRequest): Promise<AuthResponse> => {
    return request<AuthResponse>('/auth/register', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  },
};
