import { Component, signal } from '@angular/core';

@Component({
  selector: 'bs-landing',
  standalone: true,
  templateUrl: './landing.html',
  styleUrl: './landing.scss'
})
export class LandingComponent {
  readonly stacks = [
    {
      name: 'Everyday science',
      message:
        'A day on Venus is longer than its year: it rotates more slowly than it orbits the Sun.'
    },
    {
      name: 'Words and language',
      message: 'An anagram rearranges the letters of a word or phrase. Listen becomes silent.'
    },
    {
      name: 'Learning habits',
      message:
        'Try recalling what you learned before rereading it. Retrieval practice helps strengthen memory.'
    }
  ];
  readonly selectedStack = signal(this.stacks[0]);
  readonly year = new Date().getFullYear();
}
