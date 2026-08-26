import { Component } from '@angular/core';
import { Navigation } from '@/app/domains/admin/layout/ui/navigation';
import { User } from '@/app/domains/admin/layout/ui/user';

@Component({
  selector: 'admin-sidebar',
  imports: [Navigation, User],
  host: {
    class: 'flex w-full flex-auto flex-col',
  },
  template: `
    <!-- Header -->
    <div class="relative flex items-center gap-x-2.5 pt-5 pr-4 pb-0 pl-6">
      <!-- Logo — official HP mark, rendered white (reversed variant) for the dark sidebar -->
      <img
        src="/images/logo/logo.svg"
        class="size-8 shrink-0 brightness-0 invert"
        alt="HP"
      />

      <div class="flex flex-col">
        <div
          class="text-on-surface text-lg leading-none font-bold tracking-wider"
        >
          TDS Fordism
        </div>
      </div>
    </div>

    <!-- Navigation -->
    <navigation class="mt-8 mb-4 flex-auto" />

    <!-- Spacer -->
    <div class="flex-auto"></div>

    <!-- Account -->
    <user class="border-t border-neutral-200 p-3 dark:border-neutral-800" />
  `,
})
export class AdminSidebar {}
