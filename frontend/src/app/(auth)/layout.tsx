export default function AuthLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen bg-zinc-950 px-4 py-10">
      <div className="mx-auto flex w-full max-w-5xl flex-col items-center gap-6">
        {children}
      </div>
    </div>
  );
}
