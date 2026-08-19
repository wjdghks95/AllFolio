import { Outlet } from 'react-router';

export default function AuthLayout() {
  return (
    <main className="mx-auto flex min-h-screen w-full max-w-sm flex-col justify-center px-5 py-10">
      <Outlet />
    </main>
  );
}
