import { Component, signal } from '@angular/core';

@Component({
  selector: 'bs-landing',
  standalone: true,
  templateUrl: './landing.html',
  styleUrl: './landing.scss'
})
export class LandingComponent {
  // These are preview messages for visitors, separate from the saved stack catalog.
  readonly stacks = [
    {
      name: 'Words and language',
      label: 'Interview vocabulary',
      message:
        'Initiative means acting before you’re asked. In an interview: “I took the initiative to spot a repeat issue and wrote a checklist the team could use.”'
    },
    {
      name: 'Everyday science',
      label: 'Conversation starter',
      message:
        'A day on Venus is longer than its year: it rotates more slowly than it orbits the Sun.'
    },
    {
      name: 'Words and language',
      label: 'A quick joke',
      message: 'I used to be a baker, but I couldn’t make enough dough.'
    }
  ];
  readonly selectedStack = signal(this.stacks[0]);
  readonly year = new Date().getFullYear();
}
