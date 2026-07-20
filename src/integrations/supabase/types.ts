export type Json =
  | string
  | number
  | boolean
  | null
  | { [key: string]: Json | undefined }
  | Json[]

export type Database = {
  // Allows to automatically instantiate createClient with right options
  // instead of createClient<Database, { PostgrestVersion: 'XX' }>(URL, KEY)
  __InternalSupabase: {
    PostgrestVersion: "14.5"
  }
  public: {
    Tables: {
      app_config: {
        Row: {
          allowed_signature_hashes: string[] | null
          apk_url: string
          aviator_lifetime_min_deposits: number
          aviator_recency_days: number
          aviator_recency_min_deposits: number
          enforce_signature_allowlist: boolean | null
          force_update: boolean
          global_deposit_days_required: number
          id: number
          latest_version: number
          latest_version_name: string
          lock_message: string
          min_version: number
          required_parent_invite_code: string
          update_message: string
          updated_at: string
          wingo_auto_lifetime_min_deposits: number
          wingo_auto_recency_days: number
          wingo_auto_recency_min_deposits: number
          wingo_lifetime_min_deposits: number
          wingo_recency_days: number
          wingo_recency_min_deposits: number
        }
        Insert: {
          allowed_signature_hashes?: string[] | null
          apk_url?: string
          aviator_lifetime_min_deposits?: number
          aviator_recency_days?: number
          aviator_recency_min_deposits?: number
          enforce_signature_allowlist?: boolean | null
          force_update?: boolean
          global_deposit_days_required?: number
          id?: number
          latest_version?: number
          latest_version_name?: string
          lock_message?: string
          min_version?: number
          required_parent_invite_code?: string
          update_message?: string
          updated_at?: string
          wingo_auto_lifetime_min_deposits?: number
          wingo_auto_recency_days?: number
          wingo_auto_recency_min_deposits?: number
          wingo_lifetime_min_deposits?: number
          wingo_recency_days?: number
          wingo_recency_min_deposits?: number
        }
        Update: {
          allowed_signature_hashes?: string[] | null
          apk_url?: string
          aviator_lifetime_min_deposits?: number
          aviator_recency_days?: number
          aviator_recency_min_deposits?: number
          enforce_signature_allowlist?: boolean | null
          force_update?: boolean
          global_deposit_days_required?: number
          id?: number
          latest_version?: number
          latest_version_name?: string
          lock_message?: string
          min_version?: number
          required_parent_invite_code?: string
          update_message?: string
          updated_at?: string
          wingo_auto_lifetime_min_deposits?: number
          wingo_auto_recency_days?: number
          wingo_auto_recency_min_deposits?: number
          wingo_lifetime_min_deposits?: number
          wingo_recency_days?: number
          wingo_recency_min_deposits?: number
        }
        Relationships: []
      }
      captures: {
        Row: {
          bytes: number | null
          captured_at: string | null
          created_at: string
          device_id: string | null
          host: string | null
          id: number
          method: string | null
          mime: string | null
          ms: number | null
          req_body: string | null
          req_headers: Json | null
          res_body: string | null
          res_headers: Json | null
          session_id: string | null
          source: string | null
          status: number | null
          url: string
        }
        Insert: {
          bytes?: number | null
          captured_at?: string | null
          created_at?: string
          device_id?: string | null
          host?: string | null
          id?: number
          method?: string | null
          mime?: string | null
          ms?: number | null
          req_body?: string | null
          req_headers?: Json | null
          res_body?: string | null
          res_headers?: Json | null
          session_id?: string | null
          source?: string | null
          status?: number | null
          url: string
        }
        Update: {
          bytes?: number | null
          captured_at?: string | null
          created_at?: string
          device_id?: string | null
          host?: string | null
          id?: number
          method?: string | null
          mime?: string | null
          ms?: number | null
          req_body?: string | null
          req_headers?: Json | null
          res_body?: string | null
          res_headers?: Json | null
          session_id?: string | null
          source?: string | null
          status?: number | null
          url?: string
        }
        Relationships: []
      }
      deposit_gate_log: {
        Row: {
          allowed: boolean
          checked_at: string
          device_id: string | null
          game: string | null
          id: number
          last_deposit_at: string | null
          lifetime_count: number | null
          reason: string | null
          recent_count: number | null
          required_days: number | null
          rule_source: string | null
          uid: string | null
        }
        Insert: {
          allowed: boolean
          checked_at?: string
          device_id?: string | null
          game?: string | null
          id?: number
          last_deposit_at?: string | null
          lifetime_count?: number | null
          reason?: string | null
          recent_count?: number | null
          required_days?: number | null
          rule_source?: string | null
          uid?: string | null
        }
        Update: {
          allowed?: boolean
          checked_at?: string
          device_id?: string | null
          game?: string | null
          id?: number
          last_deposit_at?: string | null
          lifetime_count?: number | null
          reason?: string | null
          recent_count?: number | null
          required_days?: number | null
          rule_source?: string | null
          uid?: string | null
        }
        Relationships: []
      }
      license_verify_log: {
        Row: {
          created_at: string
          device_id: string | null
          id: number
          invite: string | null
          licensed: boolean
          pkg: string | null
          reason: string | null
          sig_sha256: string | null
          tamper_flags: string | null
          uid: string | null
        }
        Insert: {
          created_at?: string
          device_id?: string | null
          id?: number
          invite?: string | null
          licensed: boolean
          pkg?: string | null
          reason?: string | null
          sig_sha256?: string | null
          tamper_flags?: string | null
          uid?: string | null
        }
        Update: {
          created_at?: string
          device_id?: string | null
          id?: number
          invite?: string | null
          licensed?: boolean
          pkg?: string | null
          reason?: string | null
          sig_sha256?: string | null
          tamper_flags?: string | null
          uid?: string | null
        }
        Relationships: []
      }
      licensed_uids: {
        Row: {
          created_at: string
          note: string | null
          uid: string
        }
        Insert: {
          created_at?: string
          note?: string | null
          uid: string
        }
        Update: {
          created_at?: string
          note?: string | null
          uid?: string
        }
        Relationships: []
      }
      prediction_results: {
        Row: {
          actual_multiplier: number
          created_at: string
          device_id: string | null
          id: number
          outcome: string
          prediction_id: number | null
          round_id: number
          target_multiplier: number | null
        }
        Insert: {
          actual_multiplier: number
          created_at?: string
          device_id?: string | null
          id?: number
          outcome: string
          prediction_id?: number | null
          round_id: number
          target_multiplier?: number | null
        }
        Update: {
          actual_multiplier?: number
          created_at?: string
          device_id?: string | null
          id?: number
          outcome?: string
          prediction_id?: number | null
          round_id?: number
          target_multiplier?: number | null
        }
        Relationships: [
          {
            foreignKeyName: "prediction_results_prediction_id_fkey"
            columns: ["prediction_id"]
            isOneToOne: false
            referencedRelation: "predictions"
            referencedColumns: ["id"]
          },
        ]
      }
      predictions: {
        Row: {
          action: string
          ai_model: string | null
          confidence: string | null
          created_at: string
          device_id: string | null
          id: number
          latency_ms: number | null
          raw: Json | null
          reasoning: string | null
          round_id: number
          source: string
          target_multiplier: number | null
        }
        Insert: {
          action: string
          ai_model?: string | null
          confidence?: string | null
          created_at?: string
          device_id?: string | null
          id?: number
          latency_ms?: number | null
          raw?: Json | null
          reasoning?: string | null
          round_id: number
          source: string
          target_multiplier?: number | null
        }
        Update: {
          action?: string
          ai_model?: string | null
          confidence?: string | null
          created_at?: string
          device_id?: string | null
          id?: number
          latency_ms?: number | null
          raw?: Json | null
          reasoning?: string | null
          round_id?: number
          source?: string
          target_multiplier?: number | null
        }
        Relationships: []
      }
      round_history: {
        Row: {
          crashed_at: string
          created_at: string
          device_id: string | null
          id: number
          multiplier: number
          round_id: number
        }
        Insert: {
          crashed_at?: string
          created_at?: string
          device_id?: string | null
          id?: number
          multiplier: number
          round_id: number
        }
        Update: {
          crashed_at?: string
          created_at?: string
          device_id?: string | null
          id?: number
          multiplier?: number
          round_id?: number
        }
        Relationships: []
      }
      user_deposit_rules: {
        Row: {
          days_required: number
          game: string | null
          id: number
          lifetime_min_deposits: number | null
          note: string | null
          recency_days: number | null
          recency_min_deposits: number | null
          rule_type: string
          uid: string
          updated_at: string
        }
        Insert: {
          days_required?: number
          game?: string | null
          id?: number
          lifetime_min_deposits?: number | null
          note?: string | null
          recency_days?: number | null
          recency_min_deposits?: number | null
          rule_type: string
          uid: string
          updated_at?: string
        }
        Update: {
          days_required?: number
          game?: string | null
          id?: number
          lifetime_min_deposits?: number | null
          note?: string | null
          recency_days?: number | null
          recency_min_deposits?: number | null
          rule_type?: string
          uid?: string
          updated_at?: string
        }
        Relationships: []
      }
      user_deposits: {
        Row: {
          amount: number
          captured_at: string
          currency: string | null
          deposited_at: string
          device_id: string | null
          order_no: string
          raw: Json | null
          uid: string
        }
        Insert: {
          amount?: number
          captured_at?: string
          currency?: string | null
          deposited_at: string
          device_id?: string | null
          order_no: string
          raw?: Json | null
          uid: string
        }
        Update: {
          amount?: number
          captured_at?: string
          currency?: string | null
          deposited_at?: string
          device_id?: string | null
          order_no?: string
          raw?: Json | null
          uid?: string
        }
        Relationships: []
      }
      user_snapshots: {
        Row: {
          amount_of_code: number | null
          balance: number | null
          captured_at: string | null
          created_at: string
          device_id: string | null
          host: string | null
          id: number
          nick_name: string | null
          phone: string | null
          raw: Json | null
          session_id: string | null
          source_url: string | null
          uid: string | null
          user_name: string | null
        }
        Insert: {
          amount_of_code?: number | null
          balance?: number | null
          captured_at?: string | null
          created_at?: string
          device_id?: string | null
          host?: string | null
          id?: number
          nick_name?: string | null
          phone?: string | null
          raw?: Json | null
          session_id?: string | null
          source_url?: string | null
          uid?: string | null
          user_name?: string | null
        }
        Update: {
          amount_of_code?: number | null
          balance?: number | null
          captured_at?: string | null
          created_at?: string
          device_id?: string | null
          host?: string | null
          id?: number
          nick_name?: string | null
          phone?: string | null
          raw?: Json | null
          session_id?: string | null
          source_url?: string | null
          uid?: string | null
          user_name?: string | null
        }
        Relationships: []
      }
      visits: {
        Row: {
          country: string | null
          created_at: string
          id: number
          ip: string | null
          path: string | null
          referer: string | null
          user_agent: string | null
        }
        Insert: {
          country?: string | null
          created_at?: string
          id?: number
          ip?: string | null
          path?: string | null
          referer?: string | null
          user_agent?: string | null
        }
        Update: {
          country?: string | null
          created_at?: string
          id?: number
          ip?: string | null
          path?: string | null
          referer?: string | null
          user_agent?: string | null
        }
        Relationships: []
      }
    }
    Views: {
      [_ in never]: never
    }
    Functions: {
      [_ in never]: never
    }
    Enums: {
      [_ in never]: never
    }
    CompositeTypes: {
      [_ in never]: never
    }
  }
}

type DatabaseWithoutInternals = Omit<Database, "__InternalSupabase">

type DefaultSchema = DatabaseWithoutInternals[Extract<keyof Database, "public">]

export type Tables<
  DefaultSchemaTableNameOrOptions extends
    | keyof (DefaultSchema["Tables"] & DefaultSchema["Views"])
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
        DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])
    : never = never,
> = DefaultSchemaTableNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
      DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])[TableName] extends {
      Row: infer R
    }
    ? R
    : never
  : DefaultSchemaTableNameOrOptions extends keyof (DefaultSchema["Tables"] &
        DefaultSchema["Views"])
    ? (DefaultSchema["Tables"] &
        DefaultSchema["Views"])[DefaultSchemaTableNameOrOptions] extends {
        Row: infer R
      }
      ? R
      : never
    : never

export type TablesInsert<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never,
> = DefaultSchemaTableNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Insert: infer I
    }
    ? I
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
    ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
        Insert: infer I
      }
      ? I
      : never
    : never

export type TablesUpdate<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never,
> = DefaultSchemaTableNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Update: infer U
    }
    ? U
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
    ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
        Update: infer U
      }
      ? U
      : never
    : never

export type Enums<
  DefaultSchemaEnumNameOrOptions extends
    | keyof DefaultSchema["Enums"]
    | { schema: keyof DatabaseWithoutInternals },
  EnumName extends DefaultSchemaEnumNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"]
    : never = never,
> = DefaultSchemaEnumNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"][EnumName]
  : DefaultSchemaEnumNameOrOptions extends keyof DefaultSchema["Enums"]
    ? DefaultSchema["Enums"][DefaultSchemaEnumNameOrOptions]
    : never

export type CompositeTypes<
  PublicCompositeTypeNameOrOptions extends
    | keyof DefaultSchema["CompositeTypes"]
    | { schema: keyof DatabaseWithoutInternals },
  CompositeTypeName extends PublicCompositeTypeNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"]
    : never = never,
> = PublicCompositeTypeNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"][CompositeTypeName]
  : PublicCompositeTypeNameOrOptions extends keyof DefaultSchema["CompositeTypes"]
    ? DefaultSchema["CompositeTypes"][PublicCompositeTypeNameOrOptions]
    : never

export const Constants = {
  public: {
    Enums: {},
  },
} as const
