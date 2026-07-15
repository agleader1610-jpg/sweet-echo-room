import { Send } from "lucide-react";
import profilePic from "@/assets/profile.jpg.asset.json";

const TELEGRAM_URL = "https://t.me/not_perfect_xyz";

export function WelcomeModal({ onDismiss }: { onDismiss: () => void }) {
  const handleJoin = () => {
    window.open(TELEGRAM_URL, "_blank", "noopener,noreferrer");
    onDismiss();
  };

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/85 p-4 backdrop-blur-md">
      <div className="relative w-full max-w-sm overflow-hidden rounded-2xl border border-border bg-gradient-to-b from-card to-background shadow-2xl">
        <div className="h-24 bg-gradient-to-r from-emerald-500 via-cyan-500 to-blue-500" />
        <div className="-mt-12 flex flex-col items-center px-6 pb-6">
          <div className="rounded-full border-4 border-background bg-background shadow-xl">
            <img
              src={profilePic.url}
              alt="Profile"
              className="h-24 w-24 rounded-full object-cover"
            />
          </div>
          <h2 className="mt-3 text-center text-lg font-extrabold tracking-tight">
            𝙰ꨄ︎𝚂 𝙽σƚ 𝙿ҽɾϝҽƈƚ
          </h2>
          <p className="text-center text-xs font-medium text-muted-foreground">
            ᵃʳᵒʰⁱʳ ʲᵃᵐᵃⁱ༒
          </p>
          <p className="mt-4 text-center text-sm leading-relaxed text-foreground/90">
            স্বাগতম! 🎉 FIFA World Cup 2026 উপভোগ করুন সম্পূর্ণ{" "}
            <span className="font-semibold text-emerald-500">বিনামূল্যে</span> — কোনো
            বিজ্ঞাপন নেই, কোনো সাবস্ক্রিপশন নেই। এই উদ্যোগটি সবার জন্য।
          </p>
          <p className="mt-2 text-center text-xs text-muted-foreground">
            নতুন চ্যানেল ও আপডেট পেতে টেলিগ্রাম চ্যানেলে যুক্ত হোন 👇
          </p>
          <button
            onClick={handleJoin}
            className="mt-5 inline-flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-sky-500 to-blue-600 px-4 py-3 text-sm font-bold text-white shadow-lg transition-transform hover:scale-[1.02] active:scale-100"
          >
            <Send className="h-4 w-4" />
            Join Telegram Channel
          </button>
          <p className="mt-3 text-center text-[10px] text-muted-foreground">
            চ্যানেলে join করার পর সাইট চালু হবে
          </p>
        </div>
      </div>
    </div>
  );
}
