import React, { useState } from 'react';
import { Shield, Key, Monitor, CheckCircle, ArrowRight, Lock, AlertCircle, RefreshCw } from 'lucide-react';
import { api } from '../api';

interface SetupWizardProps {
  defaultName?: string;
  onComplete: () => void;
}

export const SetupWizard: React.FC<SetupWizardProps> = ({ defaultName = 'My-PC', onComplete }) => {
  const [name, setName] = useState(defaultName);
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [strictAutoConnect, setStrictAutoConnect] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!name.trim()) {
      setError('Please enter a PC Name');
      return;
    }
    if (!password) {
      setError('Please choose a connection password');
      return;
    }
    if (password.length < 4) {
      setError('Password must be at least 4 characters');
      return;
    }
    if (password !== confirmPassword) {
      setError('Passwords do not match');
      return;
    }

    setIsSubmitting(true);
    try {
      const ok = await api.setupServer(name.trim(), password.trim(), strictAutoConnect);
      if (ok) {
        onComplete();
      } else {
        setError('Failed to save configuration. Please try again.');
      }
    } catch (err: any) {
      setError(err?.message || 'Server setup failed');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-[#070B14]/95 backdrop-blur-xl flex items-center justify-center p-6">
      <div className="w-full max-w-lg bg-slate-900/90 border border-cyan-500/30 rounded-3xl p-8 shadow-2xl shadow-cyan-500/10 relative overflow-hidden animate-fade-in">
        {/* Glow ambient background */}
        <div className="absolute -top-24 -right-24 w-60 h-60 bg-cyan-500/10 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute -bottom-24 -left-24 w-60 h-60 bg-blue-500/10 rounded-full blur-3xl pointer-events-none" />

        {/* Header */}
        <div className="flex items-center gap-4 mb-6 relative">
          <div className="w-14 h-14 rounded-2xl bg-cyan-500/15 border border-cyan-500/40 flex items-center justify-center text-cyan-400 shrink-0">
            <Shield size={28} />
          </div>
          <div>
            <h2 className="text-xl font-extrabold text-white tracking-wide">
              Initial PC Setup
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Configure your PC's name and master password for secure wireless pairing.
            </p>
          </div>
        </div>

        {error && (
          <div className="mb-5 p-3 rounded-xl bg-rose-500/10 border border-rose-500/30 flex items-center gap-2.5 text-xs text-rose-300">
            <AlertCircle size={16} className="shrink-0 text-rose-400" />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4 relative">
          {/* PC Name Field */}
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center gap-1.5">
              <Monitor size={14} className="text-cyan-400" />
              <span>PC Broadcast Name</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Priyan-PC"
              className="w-full bg-slate-950/80 border border-slate-800 rounded-xl px-3.5 py-2.5 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500/60 transition"
              required
            />
            <span className="text-[11px] text-slate-500 mt-1 block">
              This name will be displayed when your phone scans for PCs.
            </span>
          </div>

          {/* Password Field */}
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center gap-1.5">
              <Key size={14} className="text-cyan-400" />
              <span>Master Connection Password</span>
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Enter secure password"
              className="w-full bg-slate-950/80 border border-slate-800 rounded-xl px-3.5 py-2.5 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500/60 transition"
              required
            />
          </div>

          {/* Confirm Password Field */}
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5 flex items-center gap-1.5">
              <Lock size={14} className="text-cyan-400" />
              <span>Confirm Password</span>
            </label>
            <input
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder="Re-enter password"
              className="w-full bg-slate-950/80 border border-slate-800 rounded-xl px-3.5 py-2.5 text-sm text-slate-100 placeholder-slate-600 focus:outline-none focus:border-cyan-500/60 transition"
              required
            />
          </div>

          {/* Strict Auto-Connect Policy Toggle */}
          <div
            onClick={() => setStrictAutoConnect(!strictAutoConnect)}
            className="p-3 rounded-xl bg-slate-950/60 border border-slate-800/80 hover:border-cyan-500/30 transition cursor-pointer flex items-start gap-3 select-none"
          >
            <div className={`mt-0.5 w-5 h-5 rounded-md border flex items-center justify-center transition shrink-0 ${
              strictAutoConnect
                ? 'bg-cyan-500 border-cyan-400 text-slate-950'
                : 'border-slate-700 bg-slate-900 text-transparent'
            }`}>
              <CheckCircle size={14} strokeWidth={3} />
            </div>
            <div>
              <span className="text-xs font-bold text-slate-200 block">
                Strict Auto-Connect Security (Recommended)
              </span>
              <span className="text-[11px] text-slate-400 leading-relaxed block mt-0.5">
                Only pre-configured/paired mobile devices with an authorized cryptographic token can connect automatically. Unknown devices on Wi-Fi are blocked.
              </span>
            </div>
          </div>

          {/* Windows DPAPI Badge */}
          <div className="p-2.5 rounded-xl bg-cyan-950/20 border border-cyan-500/15 flex items-center gap-2 text-[11px] text-cyan-300/80">
            <Shield size={13} className="text-cyan-400 shrink-0" />
            <span>Credentials are encrypted with hardware-bound <b>Windows DPAPI Vault</b>.</span>
          </div>

          {/* Submit Button */}
          <div className="pt-2">
            <button
              type="submit"
              disabled={isSubmitting}
              className="w-full py-3 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-slate-950 font-bold text-sm flex items-center justify-center gap-2 shadow-lg shadow-cyan-500/20 transition active:scale-98 disabled:opacity-50"
            >
              {isSubmitting ? (
                <>
                  <RefreshCw size={16} className="animate-spin text-slate-950" />
                  <span>Configuring Server...</span>
                </>
              ) : (
                <>
                  <span>Complete Setup & Launch Server</span>
                  <ArrowRight size={16} />
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
